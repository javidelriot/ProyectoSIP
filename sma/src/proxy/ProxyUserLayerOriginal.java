package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import common.FindMyIPv4;
import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.NotFoundMessage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import sipServlet.SIPServletInterface;
import sipServlet.SipServletRequestInterface;
import sipServlet.SipServletRequest;


/**
 * Lógica de “usuario” del proxy:
 *  - mantiene la tabla de registros (REGISTER)
 *  - decide a qué UA hay que reenviar cada mensaje
 */
public class ProxyUserLayerOriginal {

	// Lista de usuarios permitidos (URIs completas)
	private static final Set<String> ALLOWED_USERS = new HashSet<>(Arrays.asList(
	        "sip:alice@SMA",
	        "sip:bob@SMA",
	        "sip:charlie@SMA"
	));
	
	private final Map<String, String> servletByUserUri;


    private final boolean looseRouting;
    private String proxyIp;
    private int proxyPort;


    // Info de registro de un usuario
    private static class RegistrationInfo {
        String contact;      // "IP:puerto" tal como viene en el REGISTER
        long   expiresAtMs;  // instante (en ms) en el que caduca
    }

    // Tabla: "sip:usuario@dominio" -> RegistrationInfo
    private ProxyTransactionLayer transactionLayer;
    private Map<String, RegistrationInfo> registrations = new HashMap<>();

    public ProxyUserLayerOriginal(int listenPort, boolean looseRouting, Map<String, String> servletByUserUri )
            throws SocketException, UnknownHostException {

    	this.servletByUserUri = (servletByUserUri != null) ? servletByUserUri : new HashMap<>();
    	this.looseRouting = looseRouting;
        this.proxyPort    = listenPort;
        this.proxyIp      = FindMyIPv4.findMyIPv4Address().getHostAddress();

        this.transactionLayer = new ProxyTransactionLayer(listenPort, this, looseRouting);
    }

    // ===================== INVITE / RUTA PRINCIPAL =====================

    public void onInviteReceived(InviteMessage inviteMessage,
                                 String sourceIp,
                                 int sourcePort) throws IOException {

        String callerUri = inviteMessage.getFromUri();
        String calleeUri = inviteMessage.getToUri();
        
        String servletClassName = null;

        // 1) Prioridad al llamado
        if (servletByUserUri != null) {
            servletClassName = servletByUserUri.get(calleeUri);
            if (servletClassName == null) {
                // 2) Si el llamado no tiene servlet, miramos el llamante
                servletClassName = servletByUserUri.get(callerUri);
            }
        }

        if (servletClassName != null) {
            //handleInviteWithServlet(inviteMessage, sourceIp, sourcePort, callerUri, calleeUri, servletClassName);
            return; 
        }
        
        RegistrationInfo callerReg = getValidRegistration(callerUri);
        RegistrationInfo calleeReg = getValidRegistration(calleeUri);

        if (callerReg == null) {
            System.out.println("[Proxy] Caller NO registrado → ignorando INVITE.");
            return;
        }

        if (calleeReg == null) {
            System.out.println("[Proxy] Callee NO registrado → enviando 404");
            transactionLayer.sendInviteNotFound(inviteMessage, callerReg.contact);
            return;
        }

        // 1) Enviar 100 Trying al llamante (IP/puerto de donde vino el INVITE)
        System.out.println("[Proxy] Enviando 100 Trying al llamante");
        transactionLayer.sendTrying(inviteMessage, sourceIp, sourcePort);

        // 2) Dirección real del callee a partir del REGISTER
        String[] parts = calleeReg.contact.split(":");
        String destIp   = parts[0];
        int    destPort = Integer.parseInt(parts[1]);

        // 3) Añadir Via del proxy arriba
        inviteMessage.getVias().add(0, proxyIp + ":" + proxyPort);

        // 4) Si hay loose routing, añadimos Record-Route con la dirección del proxy
        if (looseRouting) {
            inviteMessage.setRecordRoute(proxyIp + ":" + proxyPort);
        }

        // 5) Reenviar el INVITE al UA llamado
        System.out.println("[Proxy] Reenviando INVITE al callee " +
                calleeUri + " en " + destIp + ":" + destPort);
        transactionLayer.forwardInvite(inviteMessage, destIp, destPort);
    }
    

    public void onRingingFromCallee(RingingMessage ringing) throws IOException {

        String callerUri = ringing.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) return;

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 180 Ringing al llamante");
        transactionLayer.forwardRinging(ringing, ip, port);
    }

    public void onInviteOKFromCallee(OKMessage ok) throws IOException {

        // El llamante aparece en From del 200 OK
        String callerUri = ok.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) return;

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 200 OK al llamante");
        transactionLayer.forwardInviteOk(ok, ip, port);
    }

    public void onAckFromCaller(ACKMessage ack) throws IOException {
        if (!looseRouting) {
            return;
        }

        String calleeUri = ack.getToUri();
        RegistrationInfo calleeReg = getValidRegistration(calleeUri);

        if (calleeReg == null) return;

        String[] parts = calleeReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        // Requisito: el proxy elimina Route en ACK si hay loose routing
        ack.setRoute(null);

        // Añadimos Via del proxy arriba (si viene lista de Vias)
        if (ack.getVias() != null) {
            ack.getVias().add(0, proxyIp + ":" + proxyPort);
        }

        System.out.println("[Proxy] Reenviando ACK al callee");
        transactionLayer.forwardAck(ack, ip, port);
    }


    // ===================== BYE con loose routing =====================

    /**
     * Llega un BYE al proxy (solo en loose routing).
     * Se reenvía al otro UA quitando el Route del proxy y añadiendo su Via.
     */
    public void onByeReceived(ByeMessage bye) throws IOException {
        if (!looseRouting) {
            return;
        }

        String toUri = bye.getToUri();  // destino del BYE
        RegistrationInfo destReg = getValidRegistration(toUri);

        if (destReg == null) {
            System.out.println("[Proxy] Destino del BYE NO registrado → se descarta.");
            return;
        }

        String[] parts = destReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        // Quitamos el Route (en esta práctica solo viene el del proxy)
        bye.setRoute(null);

        // Añadimos Via del proxy arriba
        bye.getVias().add(0, proxyIp + ":" + proxyPort);

        System.out.println("[Proxy] Reenviando BYE a " + toUri + " en " + ip + ":" + port);
        transactionLayer.forwardBye(bye, ip, port);
    }

    /**
     * Llega el 200 OK del BYE desde el callee.
     * Lo reenviamos al UA que envió el BYE.
     */
    public void onByeOkFromCallee(OKMessage ok) throws IOException {
        if (!looseRouting) {
            return;
        }

        // El que envió el BYE está en el To del 200 OK
        String byeOriginUri = ok.getFromUri();
        RegistrationInfo originReg = getValidRegistration(byeOriginUri);

        if (originReg == null) {
            System.out.println("[Proxy] Origen del BYE no registrado → se descarta 200 OK.");
            return;
        }

        String[] parts = originReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 200 OK al BYE hacia " +
                byeOriginUri + " en " + ip + ":" + port);
        transactionLayer.forwardByeOk(ok, ip, port);
    }

    // ===================== 486 / 408  =====================

    public void onBusyHereFromCallee(BusyHereMessage busy) throws IOException {

        String callerUri = busy.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 486 Busy Here: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 486 Busy Here al llamante "
                + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardBusyHere(busy, ip, port);
    }

    public void onRequestTimeoutFromCallee(RequestTimeoutMessage rt) throws IOException {

        String callerUri = rt.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 408 Request Timeout: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 408 Request Timeout al llamante "
                + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardRequestTimeout(rt, ip, port);
    }

    // ===================== REGISTER =====================

    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {

        String userUri  = registerMessage.getToUri();   // sip:alice@SMA
        String contact  = registerMessage.getContact(); // "IP:puerto"
        int expiresSec  = Integer.parseInt(registerMessage.getExpires());

        System.out.println("REGISTER recibido de " + userUri +
                " contact=" + contact +
                " expires=" + expiresSec + "s");

        boolean valido = isUserAllowed(userUri);  // ahora mismo siempre true

        if (!valido) {
            transactionLayer.sendRegisterResponse(registerMessage, contact, false);
            return;
        }

        RegistrationInfo info = new RegistrationInfo();
        info.contact     = contact;
        info.expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L;
        registrations.put(userUri, info);

        transactionLayer.sendRegisterResponse(registerMessage, contact, true);
        
        
    }

    // ===================== Utilidades registro =====================

    private RegistrationInfo getValidRegistration(String userUri) {
        RegistrationInfo info = registrations.get(userUri);
        if (info == null) {
            return null;
        }
        if (System.currentTimeMillis() > info.expiresAtMs) {
            return null;
        }
        return info;
    }

    private boolean isUserAllowed(String userUri) {
        // userUri viene tipo "sip:alice@SMA"
        return ALLOWED_USERS.contains(userUri);
    }


    // ===================== Arrancar escucha =====================

    public void startListening() {
        transactionLayer.startListening();
    }
}
