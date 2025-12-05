package ua;
import mensajesSIP.InviteMessage;
import java.util.Timer;

public class InviteServerTransaction {
    enum State { PROCEEDING, COMPLETED, TERMINATED }

    String callId;
    InviteMessage invite;
    State state;
    Timer ackWaitTimer;

    InviteServerTransaction(String callId, InviteMessage invite) {
        this.callId = callId;
        this.invite = invite;
        this.state = State.PROCEEDING;
    }
}