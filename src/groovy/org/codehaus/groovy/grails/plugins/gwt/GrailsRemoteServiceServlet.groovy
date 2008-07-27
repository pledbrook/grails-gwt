package org.codehaus.groovy.grails.plugins.gwt

import org.springframework.web.context.support.WebApplicationContextUtils as CtxUtils

import com.google.gwt.user.client.rpc.SerializationException
import com.google.gwt.user.server.rpc.RPC
import com.google.gwt.user.server.rpc.RPCRequest
import com.google.gwt.user.server.rpc.RemoteServiceServlet
import java.lang.reflect.UndeclaredThrowableException
import org.apache.log4j.Logger

/**
 * Custom GWT RPC servlet that dispatches client requests to Grails
 * services.
 */
class GrailsRemoteServiceServlet extends RemoteServiceServlet {
	private static final Logger log = Logger.getLogger(GrailsRemoteServiceServlet.class)

	/**
	 * Overrides the standard GWT servlet method, dispatching RPC
	 * requests to services.
	 */
	String processCall(String payload) throws SerializationException {
		// First decode the request.
		RPCRequest rpcRequest = RPC.decodeRequest(payload, null, this)

		// The request contains the method to invoke and the arguments
		// to pass.
		def serviceMethod = rpcRequest.method

		// Get the name of the interface that declares this method.
		def ifaceName = serviceMethod.declaringClass.name
		def pos = ifaceName.lastIndexOf('.')
		if (pos != -1) {
			ifaceName = ifaceName.substring(pos + 1)
		}

		// Work out the name of the Grails service to dispatch this
		// request to.
		def serviceName = ifaceName[0].toLowerCase(Locale.ENGLISH) + ifaceName.substring(1)

		// Get the Spring application context and retrieve the required
		// service from it.
		def ctx = CtxUtils.getWebApplicationContext(this.servletContext)
		def service = null;
		try {
			service = ctx.getBean(serviceName)
		}
		catch (Exception ex) {
			log.warn "Unable to find service $serviceNme during GWT RPC Dispatch: $ex.message"
			throw ex;
		}

		try {
			// Invoke the method on the service and encode the response.
			def retval = service.invokeMethod(serviceMethod.name, rpcRequest.parameters)
			return RPC.encodeResponseForSuccess(serviceMethod, retval, rpcRequest.serializationPolicy)

		}
		catch (UndeclaredThrowableException ex) {
			return RPC.encodeResponseForFailure(serviceMethod, ex.getCause(), rpcRequest.serializationPolicy)
		}
		catch (Exception ex) {
			return RPC.encodeResponseForFailure(serviceMethod, ex, rpcRequest.serializationPolicy)
		}
	}
}
