
# KIST-Project

## 📋 Proje Hakkında

**KIST-Project**, Java ve Maven kullanılarak geliştirilmiş bir uygulamadır. Bu proje, bir web sitesinden istenilen nitelikle kitapları elde etme ve ElasticSearch'e yüklemeyi sağlar. RabbitMQ kullanarak Producer-Consumer Yapısını kullanır.

---

## 🛠️ Özellikler

- **Modüler Yapı**: Maven ile modüler ve kolay yönetilebilir bir proje yapısı.

---

## 🚀 Başlarken

### Gereksinimler

Projenin çalıştırılabilmesi için aşağıdaki araçların sisteminizde kurulu olması gerekir:

- **Java JDK** (sürüm 8 veya daha yenisi)
- **ElasticSearch** (sürüm 8.15 veya daha yenisi)
- **Maven** (sürüm 3.6 veya daha yenisi)
- **RabbitMQ**

### Kurulum

1. Projeyi klonlayın veya zip dosyasını indirin ve açın:
   ```bash
   git clone [proje-repo-url]
   cd KIST-Project
   ```

2. Maven bağımlılıklarını yüklemek için:
   ```bash
   mvn clean install
   ```

---

## 📂 Proje Yapısı

```
KIST-Project/
├── src/                # Kaynak kodlar
│   ├── main/           # Ana kod
│   └── test/           # Test dosyaları
├── pom.xml             # Maven yapılandırması
├── target/             # Derlenmiş dosyalar
└── README.md           # Proje açıklaması
```

---

## 🔧 Çalıştırma

1. Uygulamayı çalıştırmak için:
   - Veri çekimi işlemi için AjaxDataFetcher adlı dosya üzerinden çalıştırılması gerekir. Producer'ın, verileri kuyruğa eklemeyi sağlar.
   - Kuyruktaki verileri ElasticSearch'e yüklemek için RabbitMQConsumer adlı dosya çalıştırılması gerekiyor.
   