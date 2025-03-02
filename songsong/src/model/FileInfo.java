package model;

import java.io.Serializable;

public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String filename;
    private long size;
    
    public FileInfo(String filename, long size) {
        this.filename = filename;
        this.size = size;
    }
    
    public String getFilename() {
        return filename;
    }
    
    public long getSize() {
        return size;
    }
    
    @Override
    public String toString() {
        return "File[name=" + filename + ", size=" + size + " bytes]";
    }
}