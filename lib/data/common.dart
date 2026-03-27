import 'package:shared_preferences/shared_preferences.dart';

class AppSetings {
  static const String _isCnOrEn = "isCnOrEn";
  static const String _enableDarkMode = "isEnableDarkMode";
  static const String _isMcpServer = "isMcpServer";
  static const String _checkUpdate = "isUpdate";
  static const String _checkWifi = "isCheckWifi";
  static const String _iMcpPort = "iMcpPort";
  static const String _sAuthToken = "sAuthToken";

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
    return prefs.getBool(_checkUpdate) ?? true;
  }

  static Future<bool> setCheckUpdate(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_checkUpdate, value);
  }

  static Future<bool> getCheckWifi() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_checkWifi) ?? true;
  }

  static Future<bool> setCheckWifi(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setBool(_checkWifi, value);
  }

  static Future<int> getMcpPort() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_iMcpPort) ?? 12345;
  }

  static Future<bool> setMcpPort(int value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setInt(_iMcpPort, value);
  }

  static Future<String> getAuthToken() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.getString(_sAuthToken) ?? "appproxy";
  }

  static Future<bool> setAuthToken(String value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    return prefs.setString(_sAuthToken, value);
  }
}
