package com.apk.claw.android.agent.langchain.http;

import com.apk.claw.android.utils.XLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.RequestBody;
import okhttp3.MediaType;

import okio.Buffer;

/**
 * Adapts OkHttp's builder to LangChain4j's HttpClientBuilder SPI.
 *
 * <p>Modified: adds a thinking-disabled interceptor for DeepSeek reasoning_content compatibility.</p>
 */
public class OkHttpClientBuilderAdapter implements HttpClientBuilder {

    private static final String TAG = "OkHttp";

    private Duration connectTimeout = Duration.ofSeconds(60);
    private Duration readTimeout = Duration.ofSeconds(300);

    private boolean fileLoggingEnabled = false;
    private File cacheDir;
    private boolean logRequestBody = false;

    public OkHttpClientBuilderAdapter() {
    }

    public OkHttpClientBuilderAdapter setFileLoggingEnabled(boolean enabled, File cacheDir) {
        this.fileLoggingEnabled = enabled;
        this.cacheDir = cacheDir;
        return this;
    }

    public OkHttpClientBuilderAdapter setLogRequestBody(boolean enabled) {
        this.logRequestBody = enabled;
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        final boolean logReqBody = this.logRequestBody;

        // ===== Thinking-disabled interceptor (for DeepSeek reasoning_content) =====
        Interceptor thinkingInterceptor = chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();

            // Only modify chat completion requests
            if (request.body() != null && path.contains("chat/completions")) {
                MediaType contentType = request.body().contentType();
                if (contentType != null && "application".equals(contentType.type())
                        && "json".equals(contentType.subtype())) {
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    String originalJson = buffer.readUtf8();

                    // Inject thinking disabled — inject after the opening brace
                    String patchedJson = originalJson.replaceFirst("\\{", "{\"thinking\":{\"type\":\"disabled\"},");

                    RequestBody newBody = RequestBody.create(patchedJson, contentType);
                    request = request.newBuilder()
                            .method(request.method(), newBody)
                            .build();
                }
            }
            return chain.proceed(request);
        };

        // Logging interceptor
        Interceptor llmLoggingInterceptor = chain -> {
            Request request = chain.request();
            long startMs = System.nanoTime();

            XLog.d(TAG, "--> " + request.method() + " " + request.url());
            if (logReqBody && request.body() != null) {
                Buffer buf = new Buffer();
                request.body().writeTo(buf);
                String body = buf.readUtf8();
                if (body.length() > 4000) {
                    XLog.d(TAG, "Request body: " + body.substring(0, 4000) + "...[truncated, total=" + body.length() + "]");
                } else {
                    XLog.d(TAG, "Request body: " + body);
                }
            }

            Response response = chain.proceed(request);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

            ResponseBody responseBody = response.body();
            String respStr = "";
            if (responseBody != null) {
                MediaType contentType = responseBody.contentType();
                respStr = responseBody.string();
                response = response.newBuilder()
                        .body(ResponseBody.create(contentType, respStr))
                        .build();
            }

            XLog.d(TAG, "<-- " + response.code() + " " + request.url() + " (" + durationMs + "ms)");
            if (!respStr.isEmpty()) {
                if (respStr.length() > 4000) {
                    XLog.d(TAG, "Response body: " + respStr.substring(0, 4000) + "...[truncated, total=" + respStr.length() + "]");
                } else {
                    XLog.d(TAG, "Response body: " + respStr);
                }
            }

            return response;
        };

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                // thinking interceptor first (modifies body before logging)
                .addInterceptor(thinkingInterceptor)
                .addInterceptor(llmLoggingInterceptor);

        if (fileLoggingEnabled && cacheDir != null) {
            builder.addInterceptor(new FileLoggingInterceptor(cacheDir));
        }

        OkHttpClient okHttpClient = builder.build();
        return new OkHttpClientAdapter(okHttpClient);
    }
}
