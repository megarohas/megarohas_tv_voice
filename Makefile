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

# Speech recognition: Vosk + JNA from Maven Central, Russian model from alphacephei.com
VOSK  := 0.3.47
JNA   := 5.13.0
MODEL := vosk-model-small-ru-0.22
MAVEN := https://repo1.maven.org/maven2
JARS  := libs/vosk/classes.jar libs/jna/classes.jar
SOS   := libs/vosk/jni/arm64-v8a/libvosk.so libs/jna/jni/arm64-v8a/libjnidispatch.so

B     := build
APK   := $(B)/tvvoice.apk
SRC   := $(shell find src -name '*.java')
RES   := $(shell find res -type f)
FILES := /sdcard/Android/data/$(PKG)/files

.PHONY: all deps install grant model start events clips mictest log clean

all: $(APK)

# No Gradle: aapt2 → javac → d8 → native libs → zipalign → apksigner.
$(APK): AndroidManifest.xml $(SRC) $(RES) $(JARS)
	rm -rf $(B) && mkdir -p $(B)/classes $(B)/lib/arm64-v8a
	$(BT)/aapt2 compile --dir res -o $(B)/res.zip
	$(BT)/aapt2 link -I $(PLATFORM) --manifest AndroidManifest.xml -o $(B)/app.unsigned.apk $(B)/res.zip
	javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $(PLATFORM):$(BT)/core-lambda-stubs.jar \
		-classpath libs/vosk/classes.jar:libs/jna/classes.jar -d $(B)/classes $(SRC)
	$(BT)/d8 --release --min-api 29 --lib $(PLATFORM) --output $(B) $$(find $(B)/classes -name '*.class') $(JARS)
	cp $(SOS) $(B)/lib/arm64-v8a/
	cd $(B) && zip -q app.unsigned.apk classes*.dex && zip -qr app.unsigned.apk lib
	$(BT)/zipalign -f -p 4 $(B)/app.unsigned.apk $(B)/app.aligned.apk
	$(BT)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:android --ks-key-alias androiddebugkey \
		--key-pass pass:android --out $@ $(B)/app.aligned.apk
	@ls -lh $@

deps: $(JARS) models/$(MODEL)/am/final.mdl

# AARs are checked against the SHA-1 Maven Central publishes next to them.
libs/vosk/classes.jar:
	tools/fetch-aar.sh $(MAVEN)/com/alphacephei/vosk-android/$(VOSK)/vosk-android-$(VOSK).aar libs/vosk

libs/jna/classes.jar:
	tools/fetch-aar.sh $(MAVEN)/net/java/dev/jna/jna/$(JNA)/jna-$(JNA).aar libs/jna

models/$(MODEL)/am/final.mdl:
	mkdir -p models && curl -fL -o models/$(MODEL).zip https://alphacephei.com/vosk/models/$(MODEL).zip
	cd models && unzip -q $(MODEL).zip && rm $(MODEL).zip

install: $(APK)
	$(ADB) install -r $(APK)

# Microphone, notification, drawing over Flip Clock, no battery limits
grant:
	$(ADB) shell pm grant $(PKG) android.permission.RECORD_AUDIO
	$(ADB) shell pm grant $(PKG) android.permission.POST_NOTIFICATIONS
	$(ADB) shell appops set $(PKG) SYSTEM_ALERT_WINDOW allow
	$(ADB) shell dumpsys deviceidle whitelist +$(PKG)

# The 87 MB model goes next to the app's data, not into the APK
model: models/$(MODEL)/am/final.mdl
	$(ADB) shell mkdir -p $(FILES)/model
	$(ADB) push models/$(MODEL)/. $(FILES)/model/

start:
	$(ADB) shell am start -n $(PKG)/.StartActivity

# What it heard (quiet mode logs everything) and the 4-second clips around each detection
events:
	$(ADB) shell cat $(FILES)/events.tsv

clips:
	mkdir -p recordings/clips && $(ADB) pull $(FILES)/clips/. recordings/clips/

# Phase-0 acoustics test: prompts on the tablet, TV box paused/played on marks, files → recordings/
mictest:
	TAB=$(TAB) BOX=$(BOX) tools/mictest.sh

log:
	$(ADB) logcat -s tvvoice

clean:
	rm -rf $(B)

# ---- the helper on the TV box (media sessions, launching apps, search) -------
BOX    ?= $(TV)
BOXPKG := com.megarohas.tvhelper
BB     := build-box
BOXAPK := $(BB)/tvhelper.apk
BOXSRC := $(shell find box/src -name '*.java')

.PHONY: box box-install link

box: $(BOXAPK)

$(BOXAPK): box/AndroidManifest.xml $(BOXSRC) $(shell find box/res -type f)
	rm -rf $(BB) && mkdir -p $(BB)/classes
	$(BT)/aapt2 compile --dir box/res -o $(BB)/res.zip
	$(BT)/aapt2 link -I $(PLATFORM) --manifest box/AndroidManifest.xml -o $(BB)/app.unsigned.apk $(BB)/res.zip
	javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $(PLATFORM):$(BT)/core-lambda-stubs.jar \
		-d $(BB)/classes $(BOXSRC)
	$(BT)/d8 --release --min-api 29 --lib $(PLATFORM) --output $(BB) $$(find $(BB)/classes -name '*.class')
	cd $(BB) && zip -q app.unsigned.apk classes.dex
	$(BT)/zipalign -f -p 4 $(BB)/app.unsigned.apk $(BB)/app.aligned.apk
	$(BT)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:android --ks-key-alias androiddebugkey \
		--key-pass pass:android --out $@ $(BB)/app.aligned.apk
	@ls -lh $@

# Notification access (= other apps' media sessions), opening apps from the background, no idle limits
box-install: $(BOXAPK)
	@test -n "$(BOX)" || { echo "set TV=<tv-ip>:5555 (adb over the network)"; exit 1; }
	adb -s $(BOX) install -r $(BOXAPK)
	adb -s $(BOX) shell cmd notification allow_listener $(BOXPKG)/.MediaWatcher
	adb -s $(BOX) shell appops set $(BOXPKG) SYSTEM_ALERT_WINDOW allow
	adb -s $(BOX) shell dumpsys deviceidle whitelist +$(BOXPKG)

# One shared secret for tablet → box requests, generated here and given to both
link:
	@test -n "$(BOX)" || { echo "set TV=<tv-ip>:5555 (adb over the network)"; exit 1; }
	@tok=$$(openssl rand -hex 16); \
	adb -s $(BOX) shell am broadcast -n $(BOXPKG)/.Setup --es token $$tok | tail -1; \
	$(ADB) shell am broadcast -n $(PKG)/.AdbControl --es helper_token $$tok | tail -1
