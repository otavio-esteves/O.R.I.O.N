package network.orion.ai.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import network.orion.ai.api.IOrionInferenceCallback;
/** Private protocol 1.x retaining the original 1.0 transactions and handshake envelope.
 * Every evolving Bundle requires schemaVersion=1 and bounded encoded size. */
interface IOrionCortex {
    Bundle getProtocolInfo();
    Bundle loadModel(in ParcelFileDescriptor model, in Bundle specification);
    Bundle startInference(in SharedMemory input, in Bundle specification, IOrionInferenceCallback callback);
    void cancelInference(String requestId);
    Bundle getRequestStatus(String requestId);
    Bundle getStatus();
    void unloadModel();
    Bundle healthCheck();
    /** Optional minor-1 issued-history capability; call only after negotiation. */
    Bundle issueRequestId();
    /** Optional minor-1 capability discovery; call after version checks, before issuing IDs. */
    Bundle getCapabilities();
}
