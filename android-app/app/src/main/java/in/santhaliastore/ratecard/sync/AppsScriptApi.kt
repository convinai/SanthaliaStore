package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * Retrofit interface for the Apps Script web app.
 *
 * We pass the URL as a `@Url` parameter on every call rather than
 * hard-coding it on the Retrofit base, because the user can change
 * the sheet URL from Settings at any time. The base URL we set on
 * Retrofit is just a placeholder that satisfies the builder.
 */
interface AppsScriptApi {

    @POST
    suspend fun call(
        @Url url: String,
        @Body body: SyncRequest<*>
    ): SyncResponse

    companion object {
        /**
         * Build a fresh Retrofit / OkHttp client. Caller is expected to
         * keep one instance for the lifetime of the process unless the
         * URL really does need to change (the URL is per-request anyway,
         * so practically we only need to rebuild on Moshi/OkHttp config
         * tweaks — i.e. never).
         */
        fun create(): AppsScriptApi {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            val logging = HttpLoggingInterceptor().apply {
                // BODY in debug, NONE in release. Apps Script may include
                // sheet contents in the response which we don't want
                // logged on user devices.
                level = HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)   // Apps Script can take a moment to spin up
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(logging)
                .build()

            // The base URL is never actually used (every call passes @Url)
            // but Retrofit requires one to construct the builder.
            return Retrofit.Builder()
                .baseUrl("https://script.google.com/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(AppsScriptApi::class.java)
        }
    }
}
