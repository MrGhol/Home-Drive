package com.example.homeserver.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static Retrofit retrofit = null;
    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_API_KEY = "api_key";

    public static ApiService getApiService(Context context) {
        if (retrofit == null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String baseUrl = prefs.getString(KEY_IP, "");
            String apiKey = prefs.getString(KEY_API_KEY, "");

            if (baseUrl.isEmpty()) {
                // Fallback or handle error
                baseUrl = "http://localhost/"; 
            }
            
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }

            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .addInterceptor(chain -> {
                        Request original = chain.request();
                        Request.Builder requestBuilder = original.newBuilder();
                        if (!apiKey.isEmpty()) {
                            requestBuilder.header("x-api-key", apiKey);
                        }
                        return chain.proceed(requestBuilder.build());
                    })
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build();
        }
        return retrofit.create(ApiService.class);
    }
    
    // Call this if the IP changes to reset the client
    public static void resetClient() {
        retrofit = null;
    }
}