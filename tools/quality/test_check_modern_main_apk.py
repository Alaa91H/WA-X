#!/usr/bin/env python3
"""Mutation tests for the ONLY official WA X APK loader: API102 with full Manager."""
import importlib.util
import tempfile
import warnings
import zipfile
from pathlib import Path

checker=Path(__file__).with_name("check_modern_main_apk.py")
spec=importlib.util.spec_from_file_location("official_checker",checker)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
original={
 "AndroidManifest.xml": b"\x03\x00\xa0\x00manifest binary",
 "assets/www/prism.js": b"/* existing WA X assets */",
 "classes.dex": b"dex\nLcom/wax/module/modern/ModernXposedEntry;\nLcom/wax/module/ModuleApplication;",
 "META-INF/xposed/java_init.list": b"com.wax.module.modern.ModernXposedEntry\n",
 "META-INF/xposed/module.prop": b"minApiVersion=102\ntargetApiVersion=102\nstaticScope=true\nautoHotReload=false\nexceptionMode=protective\n",
 "META-INF/xposed/scope.list": b"com.whatsapp\ncom.whatsapp.w4b\n",
}

def case(label,mutate,valid):
 with tempfile.TemporaryDirectory() as temp:
  apk=Path(temp)/"wax.apk"
  files=dict(original)
  mutate(files)
  with zipfile.ZipFile(apk,"w") as z:
   for name,data in files.items(): z.writestr(name,data)
  try:
   module.verify_apk(apk)
   accepted=True
  except (ValueError, OSError, UnicodeError, zipfile.BadZipFile):
   accepted=False
  if accepted!=valid: raise AssertionError(f"{label} incorrectly {'accepted' if accepted else 'rejected'}")
  print("[OK]",label)

case("exactly one API102 loader",lambda f:None,True)
case("Legacy assets xposed_init forbidden",lambda f:f.update({"assets/xposed_init":b"legacy"}),False)
case("missing loader entry",lambda f:f.pop("META-INF/xposed/java_init.list"),False)
case("missing module properties",lambda f:f.pop("META-INF/xposed/module.prop"),False)
case("missing WhatsApp scope",lambda f:f.pop("META-INF/xposed/scope.list"),False)
case("wrong API number",lambda f:f.update({"META-INF/xposed/module.prop":original["META-INF/xposed/module.prop"].replace(b"minApiVersion=102",b"minApiVersion=93")}),False)
case("wrong module class",lambda f:f.update({"META-INF/xposed/java_init.list":b"legacy.Entry"}),False)
case("expanded scope",lambda f:f.update({"META-INF/xposed/scope.list":b"com.whatsapp\nandroid\n"}),False)
case("missing Java entry",lambda f:f.update({"classes.dex":b"Lcom/wax/module/ModuleApplication;"}),False)
case("missing Manager class",lambda f:f.update({"classes.dex":b"Lcom/wax/module/modern/ModernXposedEntry;"}),False)
case("missing existing UI assets",lambda f:f.pop("assets/www/prism.js"),False)
case("legacy manifest metadata forbidden",lambda f:f.update({"AndroidManifest.xml":b"xposedminversion"}),False)
print("Official WA X modern APK gate mutation tests passed.")
