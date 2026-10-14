PKG       := com.megarohas.tvvoice
SDK       ?= $(HOME)/Library/Android/sdk
BT        ?= $(SDK)/build-tools/36.0.0
PLATFORM  ?= $(SDK)/platforms/android-36/android.jar
KEYSTORE  ?= $(HOME)/.android/debug.keystore
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

# Two devices on the bus → the tablet's ip:port goes in TAB (and ADB_SERIAL for adb itself)
TAB ?= $(ADB_SERIAL)
ADB := adb $(if $(TAB),-s $(TAB))
BOX ?= $(TV)

B   := build
APK := $(B)/tvvoice.apk
SRC := $(shell find src -name '*.java')
RES := $(shell find res -type f)

.PHONY: all install grant mictest log clean

all: $(APK)

# No Gradle: aapt2 → javac → d8 → zipalign → apksigner, a few seconds end to end.
$(APK): AndroidManifest.xml $(SRC) $(RES)
	rm -rf $(B) && mkdir -p $(B)/classes
	$(BT)/aapt2 compile --dir res -o $(B)/res.zip
	$(BT)/aapt2 link -I $(PLATFORM) --manifest AndroidManifest.xml -o $(B)/app.unsigned.apk $(B)/res.zip
	javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $(PLATFORM):$(BT)/core-lambda-stubs.jar -d $(B)/classes $(SRC)
	$(BT)/d8 --release --min-api 29 --lib $(PLATFORM) --output $(B) $$(find $(B)/classes -name '*.class')
	cd $(B) && zip -q app.unsigned.apk classes.dex
	$(BT)/zipalign -f -p 4 $(B)/app.unsigned.apk $(B)/app.aligned.apk
	$(BT)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:android --ks-key-alias androiddebugkey \
		--key-pass pass:android --out $@ $(B)/app.aligned.apk
	@ls -lh $@

install: $(APK)
	$(ADB) install -r $(APK)

grant:
	$(ADB) shell pm grant $(PKG) android.permission.RECORD_AUDIO

# Phase-0 acoustics test: prompts on the tablet, TV box paused/played on marks, files → recordings/
mictest:
	TAB=$(TAB) BOX=$(BOX) tools/mictest.sh

log:
	$(ADB) logcat -s tvvoice

clean:
	rm -rf $(B)
