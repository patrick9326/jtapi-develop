package com.example.jtapi_develop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Users")
public class User {
    
    @Id
    private String userId;
    private String roleName;
    
    public User() {}
    
    public User(String userId, String roleName) {
        this.userId = userId;
        this.roleName = roleName;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getRoleName() {
        return roleName;
    }
    
    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
}