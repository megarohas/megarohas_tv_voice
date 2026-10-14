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
