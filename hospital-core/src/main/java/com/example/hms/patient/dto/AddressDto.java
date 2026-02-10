package com.example.hms.patient.dto;

import jakarta.validation.constraints.Size;

public class AddressDto {
    @Size(max = 180)
    private String line1;

    @Size(max = 180)
    private String line2;

    @Size(max = 120)
    private String city;

    @Size(max = 120)
    private String state;

    @Size(max = 40)
    private String postalCode;

    @Size(max = 120)
    private String country;

    public String getLine1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1;
    }

    public String getLine2() {
        return line2;
    }

    public void setLine2(String line2) {
        this.line2 = line2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }
}
