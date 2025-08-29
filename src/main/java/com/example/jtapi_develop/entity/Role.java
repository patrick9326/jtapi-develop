package com.example.jtapi_develop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Roles")
public class Role {
    
    @Id
    private String roleName;
    private int guaranteedLicenses;
    private int priority;
    
    public Role() {}
    
    public Role(String roleName, int guaranteedLicenses, int priority) {
        this.roleName = roleName;
        this.guaranteedLicenses = guaranteedLicenses;
        this.priority = priority;
    }
    
    public String getRoleName() {
        return roleName;
    }
    
    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
    
    public int getGuaranteedLicenses() {
        return guaranteedLicenses;
    }
    
    public void setGuaranteedLicenses(int guaranteedLicenses) {
        this.guaranteedLicenses = guaranteedLicenses;
    }
    
    public int getPriority() {
        return priority;
    }
    
    public void setPriority(int priority) {
        this.priority = priority;
    }
}