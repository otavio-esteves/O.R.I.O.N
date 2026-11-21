package network.orion.voice.ipc;

/** Read-only first increment of the voice protocol. */
interface IOrionVoiceCoreGateway {
    int[] getProtocolVersion();
    String getCoreReadiness(int protocolMajor, int protocolMinor);
}
