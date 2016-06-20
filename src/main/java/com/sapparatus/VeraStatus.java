package com.sapparatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;

import java.text.SimpleDateFormat;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.entity.StringEntity;


import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class VeraStatus {
  private static final String USER_AGENT = "Mozilla/5.0";

  public static void main(String[] args) throws Exception {
    JSONArray parsedVeraStatus = new JSONArray();
    JSONArray oldVeraStatus = new JSONArray();

    VeraStatus veraStatus = new VeraStatus();
    String rawVeraStatus = new String();

    for (int i=0; ; i++) {
      System.out.println("Round: " + i);

      rawVeraStatus = veraStatus.sendGet();

      parsedVeraStatus = parseVeraStatus(rawVeraStatus);
      postChanged(parsedVeraStatus, oldVeraStatus);

      // sleep for 5 sec
      Thread.sleep(5000);
    }
  }

  private String sendGet() throws Exception {
    String url = "http://192.168.1.10:3480/data_request?id=sdata&output_format=json";

    HttpClient client = new DefaultHttpClient();
    HttpGet request = new HttpGet(url);
    request.addHeader("User-Agent", USER_AGENT);

    HttpResponse response = client.execute(request);

    // System.out.println("\nSending 'GET' request to URL : " + url);
    // System.out.println("Response Code : " + response.getStatusLine().getStatusCode());

    BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

    StringBuffer result = new StringBuffer();
    String line = "";
    while ((line = rd.readLine()) != null) {
      result.append(line);
    }

    return result.toString();
  }

  private static JSONArray parseVeraStatus(String result) {
    JSONParser parser = new JSONParser();
    String[] states = {"armed", "tripped", "status", "watts", "kwh", "locked", "light", "temperature",
                               "alarm", "detailedarmmode", "vendorstatus", "fanmode", "mode", "heatsp", "coolsp",
                               "hvacstate", "armmode"};
    JSONArray parsedVeraStatus = new JSONArray();

    try {
      Object obj = parser.parse(result);
      JSONObject jsonObject = (JSONObject) obj;

      // loop array
      JSONArray devices = (JSONArray) jsonObject.get("devices");
      Iterator<JSONObject> iterator = devices.iterator();
      while (iterator.hasNext()) {
        JSONObject ele = new JSONObject();
        ele = iterator.next();

         String name = (String) ele.get("name");
         Long id = (Long) ele.get("id");
         Long room = (Long) ele.get("room");

         JSONObject outEle = new JSONObject();
         outEle.put("id", id);
         outEle.put("name", name);
         outEle.put("room", room);

        for (String state: states) {
          String stateValue = (String) ele.get(state);
          if ( (stateValue != null) && (!stateValue.equals(null)) ) {
             outEle.put(state, stateValue);
          }
        }
        parsedVeraStatus.add(outEle);
      }
    } catch (ParseException e) {
            e.printStackTrace();
    }
    return parsedVeraStatus;
  }

  public static void postChanged(JSONArray parsedVeraStatus, JSONArray oldVeraStatus)  {
    Date timestamp = new Date();

    Iterator<JSONObject> iterator = parsedVeraStatus.iterator();
    while (iterator.hasNext()) {
      boolean found = false;
      JSONObject ele = iterator.next();

      JSONObject outEle = new JSONObject();
      outEle.putAll(ele);

      // System.out.println("Ele: " + ele.toString() + " " + ele.size());
      Iterator<JSONObject> oldIterator = oldVeraStatus.iterator();
      while (oldIterator.hasNext()) {
        JSONObject oldEle = oldIterator.next();
        // System.out.println("Old: " + oldEle.toString() + " " + oldEle.size());

        if ( !((Long) oldEle.get("id")).equals((Long) ele.get("id")) ) {
          // System.out.println("Id not matching. Skipping " + oldEle.get("id")  + " " + ele.get("id"));
          continue;
        }
        outEle.putAll(oldEle);

        // System.out.println("Com: " + outEle.toString() + " " + outEle.size());
        if (outEle.size() == ele.size()) {
          // same json elements
          // System.out.println("Size matching.");
          
          if (!ele.equals(oldEle)) {
            oldEle.putAll(ele);
            outEle.putAll(ele);

            System.out.println(transformObj(outEle, timestamp).toString() + " changed");

            try {
              writeVeraDataES(transformObj(outEle, timestamp));
            } catch (Exception e) {
              e.printStackTrace();
            }
          } else {
            // System.out.println(ele.toString() + " no change");
          }

          found = true;
          break;
        } else {
          // System.out.println("Size not matching.");
        }
      }
      if (!found) {
        oldVeraStatus.add(ele);

        System.out.println(transformObj(outEle, timestamp).toString() + " added");
        try {
          writeVeraDataES(transformObj(outEle, timestamp));
        } catch (Exception e) {
          e.printStackTrace();
        }
      }  
    }
  }

  private static JSONObject transformObj(JSONObject obj, Date date) {
    JSONObject outObj = new JSONObject();
    outObj.putAll(obj);

    SimpleDateFormat outputDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    outObj.put("timestamp", outputDateFormat.format(date));

    Long id = (Long) outObj.get("id");
    outObj.remove("id");
    outObj.put("SensorURI", "pmax:" + id);

    String[] floatStates = {"kwh", "watts", "temperature", "light", "heatsp", "coolsp"};
    for (String state: floatStates) {
      String strStateValue = (String) outObj.get(state);
      if ( (strStateValue != null) && (!strStateValue.equals(null)) ) {
        if (strStateValue.equals("")) { 
          strStateValue = "-1";
        }
        Float stateValue = Float.parseFloat(strStateValue);
        outObj.remove(state);
        outObj.put(state, stateValue);
      }
    }

    String[] longStates = {"armed", "tripped", "status", "locked"};
    for (String state: longStates) {
      String strStateValue = (String) outObj.get(state);
      if ( (strStateValue != null) && (!strStateValue.equals(null)) ) {
        if (strStateValue.equals("")) { 
          strStateValue = "-1";
        }
        Long stateValue = Long.parseLong(strStateValue);
        outObj.remove(state);
        outObj.put(state, stateValue);
      }
    }

    return outObj;
  }

  private static void writeVeraDataES(JSONObject veraData) throws Exception {
    String url = "http://192.168.1.16:9200/veradata/verastatus";

    HttpClient client = new DefaultHttpClient();
    HttpPost post = new HttpPost(url);

    // add header
    post.setHeader("User-Agent", USER_AGENT);

    StringEntity input = new StringEntity(veraData.toString());
    input.setContentType("application/json");
    post.setEntity(input);

    HttpResponse response = client.execute(post);

    // System.out.println("\nSending 'POST' request to URL : " + url);
    // System.out.println("Post parameters : " + post.getEntity());
    // System.out.println("Response Code : " + response.getStatusLine().getStatusCode());

    if (response.getStatusLine().getStatusCode() != 201) {
      throw new RuntimeException("Failed : HTTP error code : " + response.getStatusLine().getStatusCode());
    }

    BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

    StringBuffer result = new StringBuffer();
    String line = "";
    while ((line = rd.readLine()) != null) {
      result.append(line);
    }
    // System.out.println(result.toString());

    client.getConnectionManager().shutdown();
  }
}
