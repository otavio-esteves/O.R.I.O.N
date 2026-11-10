package network.orion.ai.api;
import android.os.Bundle;
/** Versioned, bounded schema-1 chunks only. No business authority. */
oneway interface IOrionInferenceCallback {
    void onChunk(in Bundle chunk);
}
