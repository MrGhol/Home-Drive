package com.example.homeserver.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.homeserver.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static volatile ApiService apiService = null;
    private static String cachedBaseUrl = null;
    private static String cachedApiKey = null;

    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_API_KEY = "api_key";

    // Standard timeouts for home server operations
    private static final int TIMEOUT_CONNECT = 15;
    private static final int TIMEOUT_READ = 30;
    private static final int TIMEOUT_WRITE = 30;

    /**
     * Returns a thread-safe singleton ApiService. 
     * If the server IP or API Key changes in SharedPreferences, the client is rebuilt.
     */
    public static synchronized ApiService getApiService(Context context) {
        // Use Application Context to prevent memory leaks
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        String rawIp = prefs.getString(KEY_IP, "").trim();
        String apiKey = prefs.getString(KEY_API_KEY, "").trim();

        // Validate and Normalize URL
        String baseUrl = formatUrl(rawIp);

        // Rebuild if any settings changed or if never initialized
        if (apiService == null || !baseUrl.equals(cachedBaseUrl) || !apiKey.equals(cachedApiKey)) {
            cachedBaseUrl = baseUrl;
            cachedApiKey = apiKey;

            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(BuildConfig.DEBUG
                    ? HttpLoggingInterceptor.Level.BODY
                    : HttpLoggingInterceptor.Level.BASIC);

            final String currentApiKey = apiKey;
            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .addInterceptor(chain -> {
                        Request original = chain.request();
                        Request.Builder requestBuilder = original.newBuilder();
                        // Add API Key header if present
                        if (!currentApiKey.isEmpty()) {
                            requestBuilder.header("x-api-key", currentApiKey);
                        }
                        return chain.proceed(requestBuilder.build());
                    })
                    .connectTimeout(TIMEOUT_CONNECT, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_WRITE, TimeUnit.SECONDS)
                    .build();

            try {
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .client(client)
                        .build();

                apiService = retrofit.create(ApiService.class);
            } catch (Exception e) {
                // Log error and return null to indicate misconfiguration
                e.printStackTrace();
                apiService = null; 
            }
        }

        return apiService;
    }

    /**
     * Normalizes the URL for Retrofit.
     * Ensures it has a protocol and ends with a trailing slash.
     */
    private static String formatUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "http://localhost/";
        }

        String formatted = url.trim();
        
        // Remove trailing slashes before re-adding one (handles "ip//" cases)
        while (formatted.endsWith("/")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }

        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "http://" + formatted;
        }
        
        // Retrofit requires base URL to end with /
        return formatted + "/";
    }

    /**
     * Resets the client cache. Useful for force-logout or explicit IP reset.
     */
    public static synchronized void resetClient() {
        apiService = null;
        cachedBaseUrl = null;
        cachedApiKey = null;
    }
}
