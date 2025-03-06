package directory;

import model.ClientInfo;
import model.FileInfo;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DirectoryServer implements DirectoryInterface {
    // Map of client ID to ClientInfo
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    
    // Map of filename to list of client IDs that have the file
    private final Map<String, List<String>> fileOwners = new ConcurrentHashMap<>();
    
    public DirectoryServer() {
        System.out.println("Directory Server initialized");
    }
    
    @Override
    public void registerClient(ClientInfo client) throws RemoteException {
        clients.put(client.getId(), client);
        System.out.println("Client registered: " + client.getId());
    }
    
    @Override
    public void registerFile(String clientId, FileInfo file) throws RemoteException {
        if (!clients.containsKey(clientId)) {
            throw new RemoteException("Client not registered: " + clientId);
        }
        
        fileOwners.computeIfAbsent(file.getFilename(), k -> new ArrayList<>())
                  .add(clientId);
        
        System.out.println("File registered: " + file.getFilename() + " by client: " + clientId);
    }
    
    @Override
    public Map<String, Integer> listAvailableFiles() throws RemoteException {
        Map<String, Integer> availableFiles = new HashMap<>();
        
        for (Map.Entry<String, List<String>> entry : fileOwners.entrySet()) {
            // Store filename and number of sources
            availableFiles.put(entry.getKey(), entry.getValue().size());
        }
        
        return availableFiles;
    }

    @Override
    public List<ClientInfo> getFileLocations(String filename) throws RemoteException {
        List<String> owners = fileOwners.getOrDefault(filename, new ArrayList<>());
        List<ClientInfo> locations = new ArrayList<>();
        
        for (String clientId : owners) {
            if (clients.containsKey(clientId)) {
                locations.add(clients.get(clientId));
            }
        }
        
        return locations;
    }
    
    @Override
    public void unregisterClient(String clientId) throws RemoteException {
        clients.remove(clientId);
        
        // Remove client from all file owners lists
        for (List<String> owners : fileOwners.values()) {
            owners.remove(clientId);
        }
        
        System.out.println("Client unregistered: " + clientId);
    }
    
    public static void main(String[] args) {
        try {
            DirectoryServer server = new DirectoryServer();
            DirectoryInterface stub = (DirectoryInterface) UnicastRemoteObject.exportObject(server, 0);
            
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("DirectoryService", stub);
            
            System.out.println("Directory Server ready");
        } catch (Exception e) {
            System.err.println("Directory Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}