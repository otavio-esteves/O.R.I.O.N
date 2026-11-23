package network.orion.voice.ipc;

interface IOrionVoiceMemoryCallback {
    void sample(int major, long nonce, String processName, long epoch, int bootCount,
        long sampledElapsed, long totalPssBytes);
}
