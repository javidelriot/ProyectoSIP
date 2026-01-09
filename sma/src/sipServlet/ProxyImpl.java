package sipServlet;

/**
 * Implementación de ProxyInterface.
 *
 * Desde el punto de vista del servlet, llamar a proxyTo(uri) significa
 * "quiero que la llamada continúe hacia esta URI".
 *
 * Aquí no hacemos el envío real; solo registramos la decisión en el
 * SipServletRequest para que el contenedor actúe después de doInvite().
 */
public class ProxyImpl implements ProxyInterface {

    private final SipServletRequest request;

    public ProxyImpl(SipServletRequest request) {
        this.request = request;
    }

    @Override
    public void proxyTo(String uri) {
        request.markProxyDecision(uri);
    }
}
