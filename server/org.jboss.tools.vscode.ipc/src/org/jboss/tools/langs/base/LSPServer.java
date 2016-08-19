package org.jboss.tools.langs.base;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;

import org.jboss.tools.langs.base.ResponseError.ReservedCode;
import org.jboss.tools.langs.transport.Connection;
import org.jboss.tools.langs.transport.Connection.MessageListener;
import org.jboss.tools.langs.transport.NamedPipeConnection;
import org.jboss.tools.langs.transport.TransportMessage;
import org.jboss.tools.vscode.ipc.RequestHandler;
import org.omg.CORBA.Request;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class LSPServer implements MessageListener{
	
	private class ParameterizedTypeImpl implements ParameterizedType{

		final private Type rawType;
		final private Type paramType;
		
		public ParameterizedTypeImpl(Type rawType, Type paramType) {
			this.rawType = rawType;
			this.paramType = paramType;
		}
		
		@Override
		public Type[] getActualTypeArguments() {
			return new Type[]{paramType};
		}

		@Override
		public Type getRawType() {
			return rawType;
		}

		@Override
		public Type getOwnerType() {
			return null;
		}
		
	}
	
	private class MessageJSONHandler implements JsonSerializer<Message>, JsonDeserializer<Message>{

		@Override
		public Message deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext context)
				throws JsonParseException {
			JsonObject object = jsonElement.getAsJsonObject();
			Type subType = ResponseMessage.class;
			Type paramType = null;
			if(!object.has("id")){
				subType = NotificationMessage.class;
				//TODO: Resolve result type from id. 
			}
			else if(object.has("method")){
				subType = RequestMessage.class;
			}
			String method = object.get("method").getAsString();
			LSPMethods lspm = LSPMethods.fromMethod(method);
			if(lspm == null){
				throw new UnsupportedOperationException(method +" is not available at "+LSPMethods.class.getName());
			}
			paramType = LSPMethods.fromMethod(method).getRequestType();
			return context.deserialize(jsonElement, new ParameterizedTypeImpl(subType,paramType));
		}

		@Override
		public JsonElement serialize(Message message, Type type, JsonSerializationContext context) {
			Type rawType = null; 
			Type paramType = Object.class;
			if(message instanceof NotificationMessage){
				rawType = NotificationMessage.class;
				NotificationMessage<?> nm = (NotificationMessage<?>)message;
				if(nm.getParams() != null){
					paramType = nm.getParams().getClass();
				}
			}
			if(message instanceof ResponseMessage){
				rawType = ResponseMessage.class;
				ResponseMessage<?> rm = (ResponseMessage<?>)message;
				if(rm.getResult() != null ){
					paramType = rm.getResult().getClass();
				}
			}
			if(message instanceof RequestMessage){
				rawType = RequestMessage.class;
				RequestMessage<?>rqm = (RequestMessage<?>)message;
				if(rqm.getParams() != null){
					paramType = rqm.getParams().getClass();
				}
			}
			if(rawType == null)
				throw new RuntimeException("Unrecognized message type");
			return context.serialize(message,new ParameterizedTypeImpl(rawType,paramType));
		}
		
	}
	private Connection connection;
	private final Gson gson;
	private static LSPServer instance;
	private List<RequestHandler<?, ?>> handlers;
	
	protected LSPServer(){
		GsonBuilder builder = new GsonBuilder();
		gson = builder.registerTypeAdapter(Message.class,new MessageJSONHandler())
				.create();
	}
	
	public static LSPServer getInstance(){
		if(instance == null ){
			instance = new LSPServer();
		}
		return instance;
	}
	
	public void connect(List<RequestHandler<?,?>> handlers ) throws IOException{
		this.handlers = handlers;
		final String stdInName = System.getenv("STDIN_PIPE_NAME");
		final String stdOutName = System.getenv("STDOUT_PIPE_NAME");

		connection = new NamedPipeConnection(stdOutName, stdInName);
		connection.setMessageListener(this);
		connection.start();
	}
	
	public void send (Message message){
		TransportMessage tm = new TransportMessage(gson.toJson(message));
		connection.send(tm);
	}

	@Override
	public void messageReceived(TransportMessage message) {
		Message msg = maybeParseMessage(message);
		if(msg == null )
			return;
		
		if(msg instanceof NotificationMessage){
			NotificationMessage<?> nm = (NotificationMessage<?>) msg;
			try {
				dispatchNotification(nm);
			} catch (LSPException e) {
				e.printStackTrace();
			}
		}
		
		if(msg instanceof RequestMessage){
			RequestMessage<?> rm = (RequestMessage<?>) msg;
			try{
				dispatchRequest(rm);
			}
			catch (LSPException e) {
				send(rm.respondWithError(e.getCode(),e.getMessage(),e.getData()));
			}
			catch(Exception e){
				send(rm.respondWithError(ReservedCode.INTERNAL_ERROR.code(), e.getMessage(),null));
			}
		}
	}

	/**
	 * Parses the message notifies client if parse fails and returns null 
	 * 
	 * @param message
	 * @param msg
	 * @return
	 */
	private Message maybeParseMessage(TransportMessage message) {
		try{
			return gson.fromJson(message.getContent(),Message.class);
		}catch (Exception e) {
			ResponseMessage<?> rm = new ResponseMessage();
			ResponseError error = new ResponseError();
			error.setCode(ReservedCode.PARSE_ERROR.code());
			error.setMessage(e.getMessage());
			error.setData(message.getContent());
			rm.setError(error);
			send(rm);
			return null;
		}
	}

	private void dispatchRequest(RequestMessage<?> request) throws LSPException{
		for (Iterator<RequestHandler<?, ?>> iterator = handlers.iterator(); iterator.hasNext();) {
			@SuppressWarnings("unchecked")
			RequestHandler<Object, Object> requestHandler = (RequestHandler<Object, Object>) iterator.next();
			if (requestHandler.canHandle(request.getMethod())) {
				send(request.responseWith(requestHandler.handle(request.getParams())));
				return;
			}
		}
		throw new LSPException(ReservedCode.METHOD_NOT_FOUND.code(), request.getMethod() + " is not handled", null,null);
	}

	private void dispatchNotification(NotificationMessage<?> nm) throws LSPException{
		for (Iterator<RequestHandler<?, ?>> iterator = handlers.iterator(); iterator.hasNext();) {
			@SuppressWarnings("unchecked")
			RequestHandler<Object, Object> requestHandler = (RequestHandler<Object, Object>) iterator.next();
			if(requestHandler.canHandle(nm.getMethod())){
				requestHandler.handle(nm.getParams());
				break;
			}
		}
	}
	
	public void shutdown(){
		connection.close();
	}

}
