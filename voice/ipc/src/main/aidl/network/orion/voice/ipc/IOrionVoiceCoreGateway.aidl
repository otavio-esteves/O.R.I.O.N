package network.orion.voice.ipc;

import android.os.Bundle;

import network.orion.voice.ipc.IOrionVoiceCallback;

interface IOrionVoiceCoreGateway {
    int[] getProtocolVersion();
    String getCoreReadiness(int protocolMajor, int protocolMinor);
    String submitUtterance(int protocolMajor, in Bundle request);
    Bundle getVoiceRequestStatus(int protocolMajor, String requestId, long afterSequence);
    boolean cancelVoiceRequest(int protocolMajor, String requestId);
    boolean registerVoiceCallback(int protocolMajor, String requestId, IOrionVoiceCallback callback);
    void unregisterVoiceCallback(IOrionVoiceCallback callback);
    boolean healthCheck(int protocolMajor);
}
