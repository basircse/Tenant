package com.revesoft.tms.property;

import com.revesoft.tms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "properties")
public class Property extends BaseEntity {

    public enum Type { RESIDENTIAL, COMMERCIAL, APARTMENT, SHOP, OFFICE, WAREHOUSE }

    public enum Status { ACTIVE, INACTIVE }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    private String ownerName;
    private String contactNumber;

    @Column(nullable = false)
    private int floors;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;
}
