package network.orion.voice.ipc;

import android.os.Bundle;

oneway interface IOrionVoiceCallback {
    void onResponse(in Bundle response);
}
