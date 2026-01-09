package sipServlet;

import mensajesSIP.InviteMessage;

/**
 * Implementación concreta de SipServletRequestInterface.
 *
 * Esta clase la crea SIEMPRE el contenedor (ProxyUserLayer) para cada
 * INVITE que se quiera pasar a un SIPServlet.
 *
 * Desde el punto de vista del programador del servlet, solo se ve
 * la interfaz SipServletRequestInterface. Los métodos extra de esta clase
 * sirven para que el contenedor pueda leer qué decisión ha tomado el
 * servlet (response vs proxyTo) cuando termina doInvite().
 */
public class SipServletRequest implements SipServletRequestInterface {

    // Información básica de la llamada
    private final String callerURI;
    private final String calleeURI;
    private final InviteMessage inviteMessage;
    private final String sourceIp;
    private final int sourcePort;

    // Decisión tomada por el servlet (solo una de las dos debería ser true)
    private boolean responseDecision = false;
    private int responseCode = 0;

    private boolean proxyDecision = false;
    private String proxyTargetUri = null;

    // ProxyImpl asociado a este request (para getProxy())
    private final ProxyImpl proxy;

    /**
     * Constructor pensado para que lo invoque el contenedor.
     */
    public SipServletRequest(String callerURI,
                             String calleeURI,
                             InviteMessage inviteMessage,
                             String sourceIp,
                             int sourcePort) {
        this.callerURI = callerURI;
        this.calleeURI = calleeURI;
        this.inviteMessage = inviteMessage;
        this.sourceIp = sourceIp;
        this.sourcePort = sourcePort;

        // Un ProxyImpl por request, reutilizable cuando el servlet llame a getProxy()
        this.proxy = new ProxyImpl(this);
    }

    // =============== Métodos del API (visible al servlet) ===============

    @Override
    public String getCallerURI() {
        return callerURI;
    }

    @Override
    public String getCalleeURI() {
        return calleeURI;
    }

    @Override
    public SipServletResponseInterface createResponse(int statusCode) {
        // De momento no enviamos nada. Solo devolvemos un objeto que,
        // al invocar send(), marcará la decisión en este request.
        return new SipServletResponse(this, statusCode);
    }

    @Override
    public ProxyInterface getProxy() {
        return proxy;
    }

    // =============== Métodos de uso interno por el contenedor ===============

    /**
     * Llamado desde SipServletResponse.send().
     * Marca que el servlet quiere contestar con un código concreto.
     */
    void markResponseDecision(int statusCode) {
        // Política: si ya había decisión (response o proxy), ignoramos la nueva.
        if (responseDecision || proxyDecision) {
            return;
        }
        this.responseDecision = true;
        this.responseCode = statusCode;
    }

    /**
     * Llamado desde ProxyImpl.proxyTo().
     * Marca que el servlet quiere progresar la llamada hacia targetUri.
     */
    void markProxyDecision(String targetUri) {
        if (responseDecision || proxyDecision) {
            return;
        }
        this.proxyDecision = true;
        this.proxyTargetUri = targetUri;
    }

    // Getters para que el ProxyUserLayer pueda leer la decisión

    public boolean hasResponseDecision() {
        return responseDecision;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public boolean hasProxyDecision() {
        return proxyDecision;
    }

    public String getProxyTargetUri() {
        return proxyTargetUri;
    }

    // Información de contexto útil para el contenedor

    public InviteMessage getInviteMessage() {
        return inviteMessage;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public int getSourcePort() {
        return sourcePort;
    }
}
