package com.example.homeserver.data.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.homeserver.BuildConfig;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static final String TAG = "RetrofitClient";
    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_API_KEY = "api_key";

    private static final int TIMEOUT_CONNECT = 15;
    private static final int TIMEOUT_READ = 30;

    private static volatile ApiService apiService = null;
    private static String cachedBaseUrl = null;
    private static String cachedApiKey = null;

    private RetrofitClient() { }

    public static synchronized ApiService getApiService(Context context) {
        if (context == null) return null;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String rawIp = safeTrim(prefs.getString(KEY_IP, ""));
        String apiKey = safeTrim(prefs.getString(KEY_API_KEY, ""));

        if (rawIp.isEmpty()) {
            resetCache();
            return null;
        }

        HttpUrl baseUrl = buildBaseUrl(rawIp);
        if (baseUrl == null) {
            resetCache();
            return null;
        }

        String normalizedBaseUrl = baseUrl.toString();

        if (apiService != null
                && normalizedBaseUrl.equals(cachedBaseUrl)
                && apiKey.equals(cachedApiKey)) {
            return apiService;
        }

        try {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(BuildConfig.DEBUG
                    ? HttpLoggingInterceptor.Level.BODY
                    : HttpLoggingInterceptor.Level.NONE);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .addInterceptor(new Interceptor() {
                        @Override
                        public Response intercept(Chain chain) throws java.io.IOException {
                            Request original = chain.request();
                            Request.Builder builder = original.newBuilder();

                            if (!apiKey.isEmpty()) {
                                builder.header("x-api-key", apiKey);
                            }

                            return chain.proceed(builder.build());
                        }
                    })
                    .connectTimeout(TIMEOUT_CONNECT, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
                    .build();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build();

            apiService = retrofit.create(ApiService.class);
            cachedBaseUrl = normalizedBaseUrl;
            cachedApiKey = apiKey;

            return apiService;

        } catch (Exception e) {
            Log.e(TAG, "getApiService: Failed to build Retrofit", e);
            resetCache();
            return null;
        }
    }

    private static HttpUrl buildBaseUrl(String rawUrl) {
        String url = safeTrim(rawUrl);
        if (url.isEmpty()) return null;

        if (!url.toLowerCase(Locale.US).startsWith("http://")
                && !url.toLowerCase(Locale.US).startsWith("https://")) {
            url = "http://" + url;
        }

        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return null;

        if (parsed.encodedPath().endsWith("/")) {
            return parsed;
        }

        return parsed.newBuilder()
                .addPathSegment("")
                .build();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void resetCache() {
        apiService = null;
        cachedBaseUrl = null;
        cachedApiKey = null;
    }

    public static synchronized void resetClient() {
        resetCache();
    }
}