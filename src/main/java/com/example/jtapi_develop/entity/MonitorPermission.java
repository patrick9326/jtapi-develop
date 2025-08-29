package com.example.jtapi_develop.entity;

import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "MonitorPermissions")
@IdClass(MonitorPermission.MonitorPermissionId.class)
public class MonitorPermission {
    
    @Id
    @Column(name = "supervisor_id")
    private String supervisorId;
    
    @Id
    @Column(name = "target_extension")
    private String targetExtension;
    
    public MonitorPermission() {}
    
    public MonitorPermission(String supervisorId, String targetExtension) {
        this.supervisorId = supervisorId;
        this.targetExtension = targetExtension;
    }
    
    public String getSupervisorId() {
        return supervisorId;
    }
    
    public void setSupervisorId(String supervisorId) {
        this.supervisorId = supervisorId;
    }
    
    public String getTargetExtension() {
        return targetExtension;
    }
    
    public void setTargetExtension(String targetExtension) {
        this.targetExtension = targetExtension;
    }
    
    public static class MonitorPermissionId implements Serializable {
        private String supervisorId;
        private String targetExtension;
        
        public MonitorPermissionId() {}
        
        public MonitorPermissionId(String supervisorId, String targetExtension) {
            this.supervisorId = supervisorId;
            this.targetExtension = targetExtension;
        }
        
        public String getSupervisorId() {
            return supervisorId;
        }
        
        public void setSupervisorId(String supervisorId) {
            this.supervisorId = supervisorId;
        }
        
        public String getTargetExtension() {
            return targetExtension;
        }
        
        public void setTargetExtension(String targetExtension) {
            this.targetExtension = targetExtension;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MonitorPermissionId that = (MonitorPermissionId) o;
            return supervisorId.equals(that.supervisorId) && targetExtension.equals(that.targetExtension);
        }
        
        @Override
        public int hashCode() {
            return supervisorId.hashCode() + targetExtension.hashCode();
        }
    }
}