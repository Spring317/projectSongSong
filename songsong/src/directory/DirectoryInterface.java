package directory;

import model.ClientInfo;
import model.FileInfo;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

public interface DirectoryInterface extends Remote {
    void registerClient(ClientInfo client) throws RemoteException;
    void registerFile(String clientId, FileInfo file) throws RemoteException;
    List<ClientInfo> getFileLocations(String filename) throws RemoteException;
    void unregisterClient(String clientId) throws RemoteException;
    Map<String, Integer> listAvailableFiles() throws RemoteException;
}
