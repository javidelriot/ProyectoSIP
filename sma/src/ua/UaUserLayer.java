package ua;


import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.UUID;
import java.util.Timer;

import common.FindMyIPv4;

import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.RequestTimeoutMessage;


public class UaUserLayer {
	// Estados de alto nivel del UA
	private static final int IDLE             = 0;
	private static final int OUTGOING_CALL   = 1;
	private static final int INCOMING_RINGING = 2;
	private static final int IN_CALL         = 3;

	private int state = IDLE;

	// INVITE entrante pendiente de decisión
	private InviteMessage currentIncomingInvite;

	// Timer para colgar por timeout si nadie descuelga
	private Timer incomingCallTimer;

	
	private String usuarioSip;
	private boolean debug;
	private int tiempoRegistro;

	private boolean registered = false;  // lo pondremos a true cuando llegue 200 OK

	public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(
			Arrays.asList(new Integer[] { 96, 97, 98 }));

	private UaTransactionLayer transactionLayer;

	private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
	private int rtpPort;
	private int listenPort;

	private Process vitextClient = null;
	private Process vitextServer = null;

	public UaUserLayer(String usuarioSip,
            int listenPort,
            String proxyAddress,
            int proxyPort,
            boolean debug,
            int tiempoRegistro)
throws SocketException, UnknownHostException {

		this.usuarioSip     = usuarioSip;
		this.listenPort     = listenPort;
		this.rtpPort        = listenPort + 1;
		this.debug          = debug;
		this.tiempoRegistro = tiempoRegistro;

		this.transactionLayer = new UaTransactionLayer(
				listenPort,
				proxyAddress,
				proxyPort,
				this
				);
	}


	public void startRegistration() {
	    System.out.println("Iniciando registro SIP en el proxy...");

	    // Bucle simple: manda REGISTER hasta que alguien ponga registered=true
	    // (más adelante lo refinamos con timers y máximo de reintentos, si quieres).
	    while (!registered) {
	        try {
	            sendRegisterOnce();

	            // Esperar un poco a ver si llega respuesta
	            Thread.sleep(2000);

	            if (!registered) {
	                System.out.println("No response to REGISTER, retrying...");
	            }
	        } catch (Exception e) {
	            System.err.println("Error al enviar REGISTER: " + e.getMessage());
	            e.printStackTrace();
	        }
	    }

	    System.out.println("Registro completado correctamente. Ya puedes hacer llamadas.");
	}

	
	private void sendRegisterOnce() throws IOException {
	    // Construimos el REGISTER siguiendo la API de RegisterMessage
	    RegisterMessage register = new RegisterMessage();

	    // usuarioSip puede ser "alice@SMA" o similar
	    String[] partes = usuarioSip.split("@");
	    String user   = partes[0];
	    String domain = (partes.length > 1) ? partes[1] : "SMA";

	    // 1) Línea de petición: REGISTER sip:DOMINIO SIP/2.0
	    register.setDestination("sip:" + domain);

	    // 2) Cabecera Via: nuestra IP y puerto de escucha
	    register.setVias(new ArrayList<String>(
	            Arrays.asList(this.myAddress + ":" + this.listenPort)));

	    // 3) Max-Forwards (igual que en el INVITE de ejemplo)
	    register.setMaxForwards(70);

	    // 4) To / From
	    String uriUsuario = "sip:" + user + "@" + domain;

	    register.setToName(user);
	    register.setToUri(uriUsuario);

	    register.setFromName(user);
	    register.setFromUri(uriUsuario);

	    // 5) Call-ID y CSeq
	    String callId = UUID.randomUUID().toString();
	    register.setCallId(callId);
	    register.setcSeqNumber("1");
	    register.setcSeqStr("REGISTER");

	    // 6) Contact: <sip:user@IP:puertoUA>
	    // OJO: RegisterMessage ya pone "sip:" delante, así que aquí solo user@ip:puerto
	 // El Contact debe ser solo "IP:puerto", sin usuario ni @
	 // Ejemplo: "172.21.0.140:9000"
	 String contact = myAddress + ":" + listenPort;
	 register.setContact(contact);


	    // 7) Expires y Content-Length (REGISTER sin cuerpo → 0)
	    register.setExpires(Integer.toString(tiempoRegistro)); // en segundos
	    register.setContentLength(0);

	    if (debug) {
	        System.out.println(">>> REGISTER a enviar:");
	        System.out.println(register.toStringMessage());
	    }

	    // 8) Enviar al proxy a través de la capa de transacción
	    transactionLayer.sendRegister(register);
	}

	
//	public void onInviteOKReceived(OKMessage okMessage) {
//	    System.out.println("Recibido 200 OK a INVITE desde " + okMessage.getToUri());
//	    //System.out.println("Llamada establecida (versión simple, sin ACK aún).");
//	    // Más adelante aquí generaremos y enviaremos el ACK
//	}



	public void startListeningNetwork() {
		transactionLayer.startListeningNetwork();
	}

	public void startListeningKeyboard() {
		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				prompt();
				String line = scanner.nextLine();
				if (!line.isEmpty()) {
					command(line);
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private void prompt() {
	    System.out.println("");
	    switch (state) {
	    case IDLE:
	    	System.out.println("INVITE xxx");
	        break;
	    case OUTGOING_CALL:
	        System.out.println("Llamada saliente: esperando respuesta (Ringing / 200 OK / error)...");
	        break;
	    case INCOMING_RINGING:
	        System.out.println("Llamada entrante. Comandos: ACCEPT | REJECT");
	        break;
	    case IN_CALL:
	        //System.out.println("En llamada. (de momento sin comandos adicionales)");
	        break;
	    default:
	        throw new IllegalStateException("Unexpected state: " + state);
	    }
	    System.out.print("> ");
	}


	private void command(String line) throws IOException {
	    String trimmed = line.trim();
	    String upper   = trimmed.toUpperCase();

	    if (state == IDLE) {
	        if (upper.startsWith("INVITE")) {
	            commandInvite(trimmed);
	        } else {
	            System.out.println("Bad command");
	        }
	        return;
	    }

	    if (state == INCOMING_RINGING) {
	        if ("ACCEPT".equals(upper)) {
	            acceptIncomingCall();
	        } else if ("REJECT".equals(upper)) {
	            rejectIncomingCall();
	        } else {
	            System.out.println("Comandos válidos mientras suena: ACCEPT | REJECT");
	        }
	        return;
	    }

	    // Para OUTGOING_CALL o IN_CALL, de momento no soportamos comandos
	    System.out.println("Comando no válido en el estado actual.");
	}

	
	// ¿Estoy registrado (y ya ha llegado 200 OK al REGISTER)?
	public boolean isRegistered() {
	    return registered;
	}

	private void commandInvite(String line) throws IOException {
	    if (!isRegistered()) {
	        System.out.println("No puedes hacer INVITE: el UA aún no está registrado.");
	        return;
	    }

	    stopVitextServer();
	    stopVitextClient();

	    // Ejemplo de línea: "INVITE bob"
	    String[] parts = line.split("\\s+");
	    if (parts.length < 2) {
	        System.out.println("Uso: INVITE nombreDestino");
	        return;
	    }

	    String destName = parts[1];   // "bob"
	    
	    // usuarioSip viene de args[0] en UA.java, por ej. "alice@SMA"
	    String[] userParts = usuarioSip.split("@");
	    String myUser   = userParts[0];
	    String myDomain = (userParts.length > 1) ? userParts[1] : "SMA";

	    // Construimos URIs SIP
	    String fromUri = "sip:" + myUser + "@" + myDomain; //El domain es el mismo para ambos
	    String toUri   = "sip:" + destName + "@" + myDomain;

	    System.out.println("Inviting " + toUri);

	    //runVitextClient(); // cliente de vídeo

	    String callId = UUID.randomUUID().toString();

	    // SDP con nuestra IP y puerto RTP
	    SDPMessage sdpMessage = new SDPMessage();
	    sdpMessage.setIp(this.myAddress);
	    sdpMessage.setPort(this.rtpPort);
	    sdpMessage.setOptions(RTPFLOWS);

	    InviteMessage inviteMessage = new InviteMessage();
	    inviteMessage.setDestination(toUri);
	    inviteMessage.setVias(new ArrayList<String>(
	            Arrays.asList(this.myAddress + ":" + this.listenPort)));
	    inviteMessage.setMaxForwards(70);

	    // Cabeceras To / From coherentes con nuestro usuario y el destino
	    inviteMessage.setToName(destName);
	    inviteMessage.setToUri(toUri);
	    inviteMessage.setFromName(myUser);
	    inviteMessage.setFromUri(fromUri);

	    inviteMessage.setCallId(callId);
	    inviteMessage.setcSeqNumber("1");
	    inviteMessage.setcSeqStr("INVITE");

	    // Contact: dónde nos puede contactar el otro extremo
	    inviteMessage.setContact(myAddress + ":" + listenPort);

	    // Cuerpo SDP
	    inviteMessage.setContentType("application/sdp");
	    inviteMessage.setContentLength(
	            sdpMessage.toStringMessage().getBytes().length);
	    inviteMessage.setSdp(sdpMessage);

	    transactionLayer.call(inviteMessage);
	    state = OUTGOING_CALL;
	}


	public void onRegisterOK() {
	    this.registered = true;
	    System.out.println("Recibido 200 OK al REGISTER");
	}

	public void onRegisterNotFound() {
	    System.out.println("Recibido 404 al REGISTER. Usuario no permitido. Cerrando UA.");
	    System.exit(0);
	}
	
	public void onRinging() {
	    System.out.println("[UA] El destino está sonando (180 Ringing)");
	    //Seguimos en OUTGOING_CALL
	}

	public void onInviteOKFromCallee(OKMessage ok) {
	    System.out.println("[UA] Llamada establecida (200 OK). ACK enviado.");
	    state = IN_CALL;
	}
	
	public void onInviteReceived(InviteMessage inv) {
	    //System.out.println("[UA] Me están llamando desde: " + inv.getFromUri());
	    // Arrancamos servidor de vídeo
	    //runVitextServer();
	    // Guardamos el INVITE para poder contestar después
	    this.currentIncomingInvite = inv;
	    this.state = INCOMING_RINGING;

	    // Cancelamos posible timer anterior
	    if (incomingCallTimer != null) {
	        incomingCallTimer.cancel();
	    }

	    // Creamos un timer para mandar 408 si nadie contesta en 10 s
	    incomingCallTimer = new java.util.Timer(true);
	    incomingCallTimer.schedule(new java.util.TimerTask() {
	        @Override
	        public void run() {
	            try {
	                // Solo si seguimos en estado "sonando" y no se ha decidido
	                if (state == INCOMING_RINGING && currentIncomingInvite != null) {
	                    System.out.println("[UA] Nadie descuelga → enviando 408 Request Timeout");
	                    transactionLayer.sendRequestTimeoutForInvite(currentIncomingInvite);
	                    currentIncomingInvite = null;
	                    state = IDLE;
	                }
	            } catch (IOException e) {
	                e.printStackTrace();
	            }
	        }
	    }, 10_000); // 10 segundos
	}


	public void onAckReceived() {
	    System.out.println("[UA] ACK recibido → llamada establecida.");
	    state = IN_CALL;
	}

	public void onInviteError() {
	    System.out.println("[UA] Error en llamada: ");
	    state = IDLE;
	}
	
	//private void runVitextClient() throws IOException {
//		vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
//	}

	private void stopVitextClient() {
		if (vitextClient != null) {
			vitextClient.destroy();
		}
	}

//	private void runVitextServer() throws IOException {
//		vitextServer = Runtime.getRuntime().exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
//	}

	private void stopVitextServer() {
		if (vitextServer != null) {
			vitextServer.destroy();
		}
	}
	
	public void onBusyHereFromCallee(BusyHereMessage busy) {
	    System.out.println("[UA] Llamada rechazada por el destino (486 Busy Here).");

	    // Aquí puedes añadir lógica extra si quieres:
	    // - limpiar estado de llamada saliente
	    // - mostrar info del usuario que ha rechazado: busy.getToUri() / getFromUri()
	    // - parar timers de INVITE si los tuvieras

	    state = IDLE;
	}

	public void onRequestTimeoutFromCallee(RequestTimeoutMessage rt) {
	    System.out.println("[UA] Llamada no contestada (408 Request Timeout).");

	    // Igual que arriba, aquí podrías limpiar recursos de la llamada saliente

	    state = IDLE;
	}

	private void acceptIncomingCall() throws IOException {
	    if (currentIncomingInvite == null) {
	        System.out.println("No hay llamada entrante que aceptar.");
	        return;
	    }

	    // Cancelar timer de timeout
	    if (incomingCallTimer != null) {
	        incomingCallTimer.cancel();
	        incomingCallTimer = null;
	    }

	    // Construimos SDP para nuestra respuesta (somos el llamado)
	    SDPMessage sdpMessage = new SDPMessage();
	    sdpMessage.setIp(this.myAddress);
	    sdpMessage.setPort(this.rtpPort);
	    sdpMessage.setOptions(RTPFLOWS);

	    String contact = myAddress + ":" + listenPort;
	    
	    System.out.println(currentIncomingInvite.getFromUri());
	    // Enviar 200 OK a través de la transaction layer
	    transactionLayer.sendOkForInvite(currentIncomingInvite, sdpMessage, contact);

	    System.out.println("[UA] Llamada aceptada → enviado 200 OK.");
	    // Si quieres arrancar vitext al aceptar:
	    // runVitextServer();

	    state = IN_CALL;
	}

	private void rejectIncomingCall() throws IOException {
	    if (currentIncomingInvite == null) {
	        System.out.println("No hay llamada entrante que rechazar.");
	        return;
	    }

	    // Cancelar timer de timeout
	    if (incomingCallTimer != null) {
	        incomingCallTimer.cancel();
	        incomingCallTimer = null;
	    }

	    System.out.println("[UA] Llamada rechazada → enviando 486 Busy Here");
	    transactionLayer.sendBusyForInvite(currentIncomingInvite);

	    currentIncomingInvite = null;
	    state = IDLE;
	}


}
