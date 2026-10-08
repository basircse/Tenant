package com.revesoft.tms.tenant;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    public enum Status { ACTIVE, INACTIVE, PREVIOUS }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String mobile;

    private String email;

    @Column(nullable = false)
    private String nid;

    private LocalDate dateOfBirth;
    private String presentAddress;
    private String emergencyContact;
    private String occupation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.INACTIVE;
}
