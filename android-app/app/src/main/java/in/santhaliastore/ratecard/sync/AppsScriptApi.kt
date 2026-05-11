package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
 * The body is a pre-serialised `RequestBody` rather than a typed
 * `SyncRequest<T>`. Retrofit refuses to install methods whose
 * parameter types contain wildcards (i.e. the Java view of
 * `SyncRequest<*>`), so we hand it concrete bytes — and serialise
 * with the right concrete payload type via Moshi just before the call,
 * using [envelope]. This keeps the call sites compact while pinning
 * the wire format down to the per-action payload class.
 *
 * The URL is per-call so the user can change the sheet URL from
 * Settings without us rebuilding Retrofit.
 */
interface AppsScriptApi {

    @POST
    suspend fun call(
        @Url url: String,
        @Body body: RequestBody
    ): SyncResponse

    /**
     * Pull endpoint — same wire envelope as [call] but returns the
     * typed [PullChangesResponse] (items + entries + cursor) instead
     * of the lighter [SyncResponse]. We declare a second method rather
     * than overloading `call` so each Retrofit method has a stable,
     * non-wildcard return type.
     */
    @POST
    suspend fun pullChanges(
        @Url url: String,
        @Body body: RequestBody
    ): PullChangesResponse

    /**
     * Bill image upload — same envelope shape as [call] but the
     * response carries the freshly-created Drive file id + viewer URL
     * that the caller stitches back into the bill's `imageFileIds`
     * CSV. Declared as its own method (rather than reusing [call])
     * so Retrofit installs a concrete, non-wildcard return type.
     *
     * `deleteBillImage` keeps using [call] because it only needs the
     * standard [SyncResponse] envelope.
     */
    @POST
    suspend fun uploadBillImage(
        @Url url: String,
        @Body body: RequestBody
    ): UploadBillImageResponse

    companion object {

        /** Single Moshi instance — adapters cache internally, so reuse it. */
        val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Build a `{ "action": ..., "payload": ... }` JSON body using
         * Moshi with the *concrete* payload class so field names round-
         * trip exactly — no wildcards, no surprises.
         */
        fun <T : Any> envelope(action: String, payload: T, payloadClass: Class<T>): RequestBody {
            val type = Types.newParameterizedType(SyncRequest::class.java, payloadClass)
            @Suppress("UNCHECKED_CAST")
            val adapter = moshi.adapter<SyncRequest<T>>(type)
            val json = adapter.toJson(SyncRequest(action, payload))
            return json.toRequestBody(JSON_MEDIA_TYPE)
        }

        /** Reified shortcut for callers in Kotlin. */
        inline fun <reified T : Any> envelope(action: String, payload: T): RequestBody =
            envelope(action, payload, T::class.java)

        /**
         * Build a fresh Retrofit / OkHttp client. Caller is expected to
         * keep one instance for the lifetime of the process. Building
         * Retrofit also validates the interface — a method shape that
         * Retrofit can't install (wildcards, missing annotations, etc.)
         * throws here at construction time, which our unit test exploits.
         */
        fun create(): AppsScriptApi {
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

            // Base URL is never used (every call passes @Url) but Retrofit
            // requires one to construct the builder.
            return Retrofit.Builder()
                .baseUrl("https://script.google.com/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(AppsScriptApi::class.java)
        }
    }
}
