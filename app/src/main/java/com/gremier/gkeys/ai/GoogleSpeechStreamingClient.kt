package com.gremier.gkeys.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.cloud.speech.v1.*
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope

/**
 * Google Cloud Speech-to-Text streaming recognition (StreamingRecognize RPC).
 */
class GoogleSpeechStreamingClient(
    private val apiKey: String,
    private val languageCode: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    companion object {
        private const val TAG = "GoogleSpeechStream"
        private const val TARGET = "speech.googleapis.com:443"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<StreamingRecognizeRequest>? = null
    private val pcmRecorder = PcmAudioRecorder()

    fun start(scope: CoroutineScope) {
        stop()
        try {
            channel = ManagedChannelBuilder.forTarget(TARGET)
                .useTransportSecurity()
                .build()

            val metadata = Metadata().apply {
                put(
                    Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER),
                    apiKey
                )
            }

            val stub = SpeechGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))

            val responseObserver = object : StreamObserver<StreamingRecognizeResponse> {
                override fun onNext(response: StreamingRecognizeResponse) {
                    if (response.resultsCount == 0) return
                    val result = response.getResults(response.resultsCount - 1)
                    val text = result.alternativesList.firstOrNull()?.transcript?.trim().orEmpty()
                    if (text.isEmpty()) return
                    mainHandler.post {
                        if (result.isFinal) onFinal(text) else onPartial(text)
                    }
                }

                override fun onError(t: Throwable) {
                    Log.e(TAG, "Streaming STT error", t)
                    mainHandler.post { onError(t) }
                }

                override fun onCompleted() {
                    Log.d(TAG, "Streaming STT completed")
                }
            }

            requestObserver = stub.streamingRecognize(responseObserver)

            val recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(PcmAudioRecorder.SAMPLE_RATE)
                .setLanguageCode(languageCode)
                .setEnableAutomaticPunctuation(true)
                .build()

            val streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .setInterimResults(true)
                .setSingleUtterance(false)
                .build()

            requestObserver?.onNext(
                StreamingRecognizeRequest.newBuilder()
                    .setStreamingConfig(streamingConfig)
                    .build()
            )

            pcmRecorder.start(scope) { chunk ->
                try {
                    requestObserver?.onNext(
                        StreamingRecognizeRequest.newBuilder()
                            .setAudioContent(ByteString.copyFrom(chunk))
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send audio chunk", e)
                }
            }
        } catch (e: Exception) {
            onError(e)
            stop()
        }
    }

    fun stop() {
        try {
            pcmRecorder.stop()
        } catch (_: Exception) {
        }
        try {
            requestObserver?.onCompleted()
        } catch (_: Exception) {
        }
        requestObserver = null
        try {
            channel?.shutdownNow()
        } catch (_: Exception) {
        }
        channel = null
    }
}
