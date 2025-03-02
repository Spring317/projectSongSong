package model;

import java.io.Serializable;

public class ClientInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String id;
    private String host;
    private int port;
    
    public ClientInfo(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }
    
    public String getId() {
        return id;
    }
    
    public String getHost() {
        return host;
    }
    
    public int getPort() {
        return port;
    }
    
    @Override
    public String toString() {
        return "Client[id=" + id + ", host=" + host + ", port=" + port + "]";
    }
}