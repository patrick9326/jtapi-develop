package com.example.jtapi_develop.repository;

import com.example.jtapi_develop.entity.MonitorPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MonitorPermissionRepository extends JpaRepository<MonitorPermission, MonitorPermission.MonitorPermissionId> {
    
    @Query("SELECT mp.targetExtension FROM MonitorPermission mp WHERE mp.supervisorId = :supervisorId")
    List<String> findTargetExtensionsBySupervisorId(@Param("supervisorId") String supervisorId);
    
    @Query(value = "SELECT target_extension FROM MonitorPermissions WHERE supervisor_id = :supervisorId", nativeQuery = true)
    List<String> findTargetExtensionsBySupervisorIdNative(@Param("supervisorId") String supervisorId);
    
    @Query(value = "SELECT supervisor_id FROM MonitorPermissions WHERE target_extension = :targetExtension", nativeQuery = true)
    List<String> findSupervisorsByTargetExtension(@Param("targetExtension") String targetExtension);
    
    @Query(value = "SELECT DISTINCT target_extension FROM MonitorPermissions", nativeQuery = true)
    List<String> findAllTargetExtensions();
}