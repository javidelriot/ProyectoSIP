package ua;
import mensajesSIP.InviteMessage;
import java.util.Timer;

//Transacción INVITE del lado llamante
public class InviteClientTransaction {
 enum State { CALLING, PROCEEDING, COMPLETED, TERMINATED }

 	String callId;
 	InviteMessage invite;
 	State state;
 	Timer terminationTimer;

 InviteClientTransaction(String callId, InviteMessage invite) {
     this.callId = callId;
     this.invite = invite;
     this.state = State.CALLING;
 }
}