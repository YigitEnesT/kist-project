package com.mycompany.kist.project;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Book {

    @JsonProperty("materyal_turu")
    private String materyalTuru = null;

    @JsonProperty("baslik")
    private String baslik = null;

    @JsonProperty("yazar")
    private String yazar = null;

    @JsonProperty("yayin_yili")
    private Integer yayinYili = null;

    @JsonProperty("basi")
    private String basi = null;

    @JsonProperty("dil")
    private String dil = null;

    @JsonProperty("konu")
    private String konu = null;

    @JsonProperty("kutuphane")
    private String kutuphane = null;

    // Getters ve Setters
    public String getMateryalTuru() {
        return materyalTuru;
    }

    public void setMateryalTuru(String materyalTuru) {
        this.materyalTuru = materyalTuru;
    }

    public String getBaslik() {
        return baslik;
    }

    public void setBaslik(String baslik) {
        this.baslik = baslik;
    }

    public String getYazar() {
        return yazar;
    }

    public void setYazar(String yazar) {
        this.yazar = yazar;
    }

    public Integer getYayinYili() {
        return yayinYili;
    }

    public void setYayinYili(Integer yayinYili) {
        this.yayinYili = yayinYili;
    }

    public String getBasi() {
        return basi;
    }

    public void setBasi(String basi) {
        this.basi = basi;
    }

    public String getDil() {
        return dil;
    }

    public void setDil(String dil) {
        this.dil = dil;
    }

    public String getKonu() {
        return konu;
    }

    public void setKonu(String konu) {
        this.konu = konu;
    }

    public String getKutuphane() {
        return kutuphane;
    }

    public void setKutuphane(String kutuphane) {
        this.kutuphane = kutuphane;
    }

    public String getAllData() {
        StringBuilder sb = new StringBuilder();

        // Tüm alanların bilgilerini ekle
        sb.append("Materyal Türü: ").append(materyalTuru).append("\n");
        sb.append("Başlık: ").append(baslik).append("\n");
        sb.append("Yazar: ").append(yazar).append("\n");
        sb.append("Yayın Yılı: ").append(yayinYili).append("\n");
        sb.append("Bası: ").append(basi).append("\n");
        sb.append("Dil: ").append(dil).append("\n");
        sb.append("Konu: ").append(konu).append("\n");
        sb.append("Kütüphane: ").append(kutuphane).append("\n");

        return sb.toString();
    }

}
