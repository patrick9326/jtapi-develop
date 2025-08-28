package com.example.jtapi_develop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    private String roleName;
    private int guaranteedLicenses;
    private int priority;

    // Getters and Setters
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public int getGuaranteedLicenses() { return guaranteedLicenses; }
    public void setGuaranteedLicenses(int guaranteedLicenses) { this.guaranteedLicenses = guaranteedLicenses; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}