package network.orion.voice.ipc;

import network.orion.voice.ipc.IOrionPushToTalkCallback;

interface IOrionPushToTalk {
    void begin(int protocolMajor, String sessionId, IOrionPushToTalkCallback callback);
    void finishCapture(int protocolMajor, String sessionId);
    void cancel(int protocolMajor, String sessionId);
}
