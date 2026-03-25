import 'package:shared_preferences/shared_preferences.dart';

class AppSetings {
  static const String _isCnOrEn = "isCnOrEn";
  static const String _enableDarkMode = "isEnableDarkMode";
  static const String _isMcpServer = "isMcpServer";
  static const String _CheckUpdate = "isUpdate";
  static const String _CheckWifi = "isCheckWifi";

  static Future<bool> getCnOrEn() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_isCnOrEn) ?? true;
  }

  static Future<bool> setCnOrEn(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_isCnOrEn, value);
  }

  static Future<bool> getEnableDarkMode() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_enableDarkMode) ?? false;
  }

  static Future<bool> setEnableDarkMode(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_enableDarkMode, value);
  }

  static Future<bool> getMcpServer() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_isMcpServer) ?? false;
  }

  static Future<bool> setMcpServer(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_isMcpServer, value);
  }

  static Future<bool> getCheckUpdate() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_CheckUpdate) ?? true;
  }

  static Future<bool> setCheckUpdate(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_CheckUpdate, value);
  }

  static Future<bool> getCheckWifi() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_CheckWifi) ?? true;
  }

  static Future<bool> setCheckWifi(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_CheckWifi, value);
  }
}
