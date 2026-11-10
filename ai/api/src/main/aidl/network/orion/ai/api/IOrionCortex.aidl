package network.orion.ai.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import network.orion.ai.api.IOrionInferenceCallback;
/** Private protocol 1.0. Every evolving Bundle requires schemaVersion=1 and bounded encoded size. */
interface IOrionCortex {
    Bundle getProtocolInfo();
    Bundle loadModel(in ParcelFileDescriptor model, in Bundle specification);
    Bundle startInference(in SharedMemory input, in Bundle specification, IOrionInferenceCallback callback);
    void cancelInference(String requestId);
    Bundle getRequestStatus(String requestId);
    Bundle getStatus();
    void unloadModel();
    Bundle healthCheck();
}
