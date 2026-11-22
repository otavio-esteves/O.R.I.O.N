package network.orion.voice.ipc;

oneway interface IOrionPushToTalkCallback {
    void onState(String sessionId, String state, String text, boolean requiresConfirmation);
}
