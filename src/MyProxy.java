import java.io.*;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class MyProxy extends Thread {

    Socket socket;//浏览器的socket
    String targetHost;//服务器主机名
    String targetPort;//服务器端口
    InputStream inputStream_client;//读取浏览器发过来的请求的流
    OutputStream outputStream_client;//将数据发送到浏览器的流
    PrintWriter outPrintWriter_client;//向浏览器写入数据
    BufferedReader bufferedReader_client;//缓冲来自浏览器的请求

    Socket accessSocket;//与网站连接的socket

    InputStream inputStream_Web;//读取从网站发回的响应
    OutputStream outputStream_Web;//向网站发送请求
    PrintWriter outPrintWriter_Web;//向网站发送请求
    BufferedReader bufferedReader_web;//缓存网站发送的请求

    //String cacheFilePath;

    File file = null;
    FileInputStream fileInputStream;
    String url = "";
    ArrayList<String> cache;
    int cacheIndex = -1;
    boolean has_cache_no_timestamp = false;

    public MyProxy(Socket inputSocket) throws IOException {

        socket = inputSocket; //与客户端的socket
        System.out.print("获取到socket host：" + inputSocket.getInetAddress() + " port:" + inputSocket.getPort() + "\n");

        inputStream_client = socket.getInputStream();//创建从浏览器获取请求的输入流
        bufferedReader_client = new BufferedReader(new InputStreamReader(inputStream_client));
        outputStream_client = socket.getOutputStream();//创建向浏览器发送响应的流
        outPrintWriter_client = new PrintWriter(outputStream_client);

        /** 读取缓存 */
        file = new File(HttpProxy.cachePath);
        if (!file.exists()) {
            file.createNewFile();
        }
        fileInputStream = new FileInputStream(HttpProxy.cachePath);
        cache = readCache(fileInputStream);
        System.out.println("从缓存文件中读取到：" + cache.size() + "行");

        start();
    }

    public void run() {
        try {
            //socket.setSoTimeout(HttpProxy.TIMEOUT);//设置最大等待时间，超过则自动断开连接

            String buffer;
            buffer = bufferedReader_client.readLine();//从浏览器读取第一行请求
            System.out.println("===处理浏览器请求报文===");
            System.out.println("读取请求行：" + buffer);
            url = getURL(buffer);

            /** 过滤 */
            if (buffer.contains("CONNECT") || buffer.contains("google") || buffer.contains("c.gj.qq.com")) {
                System.out.println("请求：" + buffer + "已被过滤，结束");
                return;//此线程结束
            }

            /** 将请求写入缓存文件,如果缓存中已经有相同的请求，不再写入 */
            System.out.println("===查找缓存中===");
            boolean has_in_cache_already = false;
            for (String iter : cache) {
                if (iter.equals(buffer)) {
                    System.out.println("缓存中有该请求");
                    has_in_cache_already = true;//该请求缓存中已有，不写入
                    break;
                }
            }

            if (has_in_cache_already == false) {
                System.out.println("缓存中没有找到该请求，将该请求写入缓存");
                String temp = buffer + "\r\n";
                write_cache(temp.getBytes(), 0, temp.length()); 
            }

            if (has_in_cache_already == true) { //缓存命中，检查缓存是否超过用户设置时间
                //获取缓存中对应该请求的响应报文的Date
                System.out.println("===检查缓存是否超过用户设置时间===");
                String dateOfResponse = findDateOfRespons(cache, buffer);
                System.out.println("提取字段 Date：" + dateOfResponse);

                if (dateOfResponse != null) {
                    //比较当前系统时间与date的间隔是否超过用户设置时间  用户设置时间：120s
                    //获取当前系统时间
                    //SimpleDateFormat simpleDateFormat = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss");

                    if (hasExpired(dateOfResponse)) {
                        System.out.println("缓存已超期");

                    } else {
                        int contentindex = 0;
                        String temp_response = "";
                        System.out.println("缓存未超期");
                        if (cacheIndex != -1)
                            for (int i = cacheIndex + 1; i < cache.size(); i++) {
                                if (cache.get(i).contains("http://")) 
                                    break;
                                temp_response += cache.get(i);
                                temp_response += "\r\n";

                            }
                        System.out.println("===将缓存中的以下数据发回浏览器===");
                        System.out.println(temp_response);
                        outputStream_client.write(temp_response.getBytes(), 0, temp_response.getBytes().length);
                        outputStream_client.write("\r\n".getBytes(), 0, "\r\n".getBytes().length);
                        outputStream_client.flush();
                        return;
                    }

                }
            }

            /**缓存未命中或缓存超期，开始连接远程服务器*/

            /** 提取目标主机的主机名和端口 */
            String[] HostandPort = new String[2];
            if (buffer != null)
                HostandPort = findHostandPort(buffer);
            targetHost = HostandPort[0];
            targetPort = HostandPort[1];

            System.out.println("===与远程服务器建立连接===");
            System.out.println("从请求中提取到网站的主机名:" + targetHost + " 端口号: " + targetPort);

            /** 尝试与目标主机连接 */
            System.out.println("正在连接中");
            int retry = HttpProxy.RETRIEVE;
            while (retry-- != 0 && (targetHost != null)) {
                try {
                    accessSocket = new Socket(targetHost, Integer.parseInt(targetPort));
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Thread.sleep(HttpProxy.CONNECT_PAUSE);
            }
            if (accessSocket != null) {//成功建立连接
                
                System.out.println("成功与网站建立连接，准备向" + targetHost+"发送请求");
                //accessSocket.setSoTimeout(HttpProxy.TIMEOUT);
                inputStream_Web = accessSocket.getInputStream();
                bufferedReader_web = new BufferedReader(new InputStreamReader(inputStream_Web));
                outPrintWriter_Web = new PrintWriter(accessSocket.getOutputStream());//用于向网站发送请求


                /** 如果缓存文件为空 */
                if (cache.size() == 0 || has_in_cache_already == false) {
                    /** 将请求直接发往网站，并获取响应，记录响应至缓存 */
                    sendRequestToInternet(buffer); //向网站发送请求
                    transmitResponseToClient(); 
                } else {//缓存文件不为空，读取缓存

                    String modifyTime;
                    String info = "";
                    modifyTime = findModifyTime(cache, buffer);//提取modifytime

                    if(modifyTime == null){
                        System.out.println("该响应不含Last-Modify");
                    }else {
                        System.out.println("提取到该请求的响应的Last-Modify：" + modifyTime);
                    }

                    if (modifyTime != null || has_cache_no_timestamp) {

                        /** 如果缓存的内容里面该请求是没有Last-Modify属性的，就不用向服务器查询If-Modify了，否则向服务器查询If-Modify */
                        if (!has_cache_no_timestamp) {

                            buffer += "\r\n";
                            outPrintWriter_Web.write(buffer);
                            System.out.print("===向服务器发送请求===:\n" + buffer);
                            String str1 = "Host: " + targetHost + "\r\n";
                            outPrintWriter_Web.write(str1);
                            String str = "If-modified-since: " + modifyTime
                                    + "\r\n";
                            outPrintWriter_Web.write(str);
                            outPrintWriter_Web.write("\r\n");
                            outPrintWriter_Web.flush();
                            System.out.print(str1);
                            System.out.print(str);

                            info = bufferedReader_web.readLine();
                            System.out.println("\n");
                            System.out.println("===处理远程服务器响应报文===");

                            System.out.println("响应行：" + info);
                        }

                        if (info.contains("Not Modified") || has_cache_no_timestamp) {//如果服务器给回的响应是304 Not Modified，就将缓存的数据直接发送给浏览器

                            int contentindex = 0;
                            String temp_response = "";
                            System.out.println("缓存有效");

                            if (cacheIndex != -1)
                                for (int i = cacheIndex + 1; i < cache.size(); i++) {
                                    if (cache.get(i).contains("http://"))
                                        break;
                                    temp_response += cache.get(i);
                                    temp_response += "\r\n";

                                }
                            System.out.println("===将缓存中的以下数据发回浏览器===");
                            System.out.println( temp_response);
                            outputStream_client.write(temp_response.getBytes(), 0, temp_response.getBytes().length);
                            outputStream_client.write("\r\n".getBytes(), 0, "\r\n".getBytes().length);
                            outputStream_client.flush();

                        } else {
                            /** 将服务器的响应直接转发到浏览器，并记录缓存 */
                            System.out.println("缓存失效");
                            transmitResponseToClient();
                        }
                    } else {
                        /**缓存中没有找到之前的记录，直接将请求发送给网站，并接收响应，将响应写入缓存 */
                        sendRequestToInternet(buffer);
                        transmitResponseToClient();
                    }

                }
            } else {
                System.out.println("与网站连接不成功");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 将请求发送给网站
     *
     * @param buffer 请求的第一行报文
     * @throws IOException
     */
    private void sendRequestToInternet(String buffer) throws IOException {


        while (!buffer.equals("")) {
            buffer += "\r\n";
            outPrintWriter_Web.write(buffer);
            System.out.print(buffer );
            buffer = bufferedReader_client.readLine();
        }
        outPrintWriter_Web.write("\r\n");
        outPrintWriter_Web.flush();

    }

    /**
     * 提取主机名和端口
     *
     * @param content 待提取的报文，这是请求的第一行
     * @return
     */
    private String[] findHostandPort(String content) {
        String host = null;
        String port = null;
        String[] result = new String[2];
        int index;
        int portIndex;
        String temp;

        StringTokenizer stringTokenizer = new StringTokenizer(content);
        stringTokenizer.nextToken();//丢弃GET等方法名
        temp = stringTokenizer.nextToken();//含主机名和端口的url

        host = temp.substring(temp.indexOf("//") + 2);//比如 http://news.sina.com.cn/gov/2017-12-13/doc-ifypsqiz3904275.shtml -> news.sina.com.cn/gov/2017-12-13/doc-ifypsqiz3904275.shtml
        index = host.indexOf("/");
        if (index != -1) {
            host = host.substring(0, index);//比如 news.sina.com.cn/gov/2017-12-13/doc-ifypsqiz3904275.shtml -> news.sina.com.cn
            portIndex = host.indexOf(":");
            if (portIndex != -1) {
                port = host.substring(portIndex + 1);//比如 www.ghostlwb.com:8080 -> 8080
                host = host.substring(0, portIndex);
            } else {//没有找到端口号，则加上默认端口号80
                port = "80";
            }
        }
        result[0] = host;
        result[1] = port;
        return result;
    }

    /**
     * 提取URL
     *
     * @param firstline 请求报文的第一行
     * @return
     */
    private String getURL(String firstline) {
        StringTokenizer stringTokenizer = new StringTokenizer(firstline);
        stringTokenizer.nextToken();//去掉get等方法
        return stringTokenizer.nextToken();
    }

    /**
     * 从网站接收响应，发送给浏览器，并将响应写入缓存
     *
     * @throws IOException
     */
    private void transmitResponseToClient() throws IOException {
        System.out.println("===将远程服务器的响应发回浏览器===");
        byte[] bytes = new byte[2048];
        int length = 0;

        while (true) {
            if ((length = inputStream_Web.read(bytes)) > 0) {
                outputStream_client.write(bytes, 0, length);
                String show_response = new String(bytes, 0, bytes.length);
                System.out.println(show_response);
                write_cache(bytes, 0, length);
                write_cache("\r\n".getBytes(), 0, 2);
                continue;
            }
            break;
        }

        outPrintWriter_client.write("\r\n");
        outPrintWriter_client.flush();
    }

    /**
     * 从文件中读取缓存内容，按行读取
     *
     * @param fileInputStream
     * @return
     */
    private ArrayList<String> readCache(FileInputStream fileInputStream) {
        ArrayList<String> result = new ArrayList<>();
        String temp;
        BufferedReader br = new BufferedReader(new InputStreamReader(fileInputStream));
        try {
            while ((temp = br.readLine()) != null) {
                result.add(temp);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 将内容写入缓存
     *
     * @param c
     * @throws IOException
     */
    private void write_cache(int c) throws IOException {
        HttpProxy.writeCache.write((char) c);
    }

    private void write_cache(byte[] bytes, int offset, int len)
            throws IOException {
        for (int i = 0; i < len; i++)
            write_cache((int) bytes[offset + i]);
    }

    /**
     * 提取modifytime
     *
     * @param cache_temp
     * @param request
     * @return
     */
    private String findModifyTime(ArrayList<String> cache_temp, String request) {
        String LastModifiTime = null;
        int startSearching = 0;
        has_cache_no_timestamp = false;

        System.out.println("正在查找该请求对应的响应的Last-Modify：" + request);
        for (int i = 0; i < cache_temp.size(); i++) {

            if (cache_temp.get(i).equals(request)) {//缓存中有该请求
                startSearching = i;
                cacheIndex = i;
                for (int j = startSearching + 1; j < cache_temp.size(); j++) { 
                    if (cache_temp.get(j).contains("http://")) //已到下一个请求，说明该请求无响应记录
                        break;
                    if (cache_temp.get(j).contains("Last-Modified:")) { //该响应包含Last-Modified
                        //LastModifiTime=cacheFilePath.substring(cache_temp.get(j).indexOf("Last-Modified:"));
                        //LastModifiTime=cache_temp.get(j).substring(cache_temp.get(j).indexOf("Last-Modified:"));
                        int index = cache_temp.get(j).indexOf("Last-Modified:");
                        LastModifiTime = cache_temp.get(j).substring(index + "Last-Modified:".length());

                        return LastModifiTime;//提取到Last-Modified时间
                    }
                    if (cache_temp.get(j).contains("<html>")) {  // 该响应没有Last-Modified属性
                        has_cache_no_timestamp = true;
                        return LastModifiTime;
                    }
                }
            }
        }

        return LastModifiTime;
    }


    private String findDateOfRespons(ArrayList<String> cache_temp, String request) {
        String dateOfRespons = null;
        int startSearching = 0;

        System.out.println("正在查找该请求对应的响应的Date：" + request);
        for (int i = 0; i < cache_temp.size(); i++) {

            if (cache_temp.get(i).equals(request)) {//缓存命中
                startSearching = i;
                cacheIndex = i;
                for (int j = startSearching + 1; j < cache_temp.size(); j++) { 
                    if (cache_temp.get(j).contains("http://")) //已到下一个请求，说明该请求无响应记录
                        break;
                    if (cache_temp.get(j).contains("Date:")) { //该响应包含Date:

                        int index = cache_temp.get(j).indexOf("Date:");
                        dateOfRespons = cache_temp.get(j).substring(index + "Date:".length());

                        return dateOfRespons;//提取到Last-Modified时间
                    }
                    if (cache_temp.get(j).contains("<html>")) {  // 该响应没有Date:属性

                        return dateOfRespons;
                    }
                }
            }
        }

        return dateOfRespons;
    }

    public static Date parseGMT(String strDate) throws ParseException {
        if (strDate != null && strDate.trim().length() > 0) {
            strDate = strDate.substring(6, 26).replace(" ", "/");
            strDate = strDate.replace("Jan", "01");
            strDate = strDate.replace("Feb", "02");
            strDate = strDate.replace("Mar", "03");
            strDate = strDate.replace("Apr", "04");
            strDate = strDate.replace("May", "05");
            strDate = strDate.replace("Jun", "06");
            strDate = strDate.replace("Jul", "07");
            strDate = strDate.replace("Aug", "08");
            strDate = strDate.replace("Sep", "09");
            strDate = strDate.replace("Oct", "10");
            strDate = strDate.replace("Nov", "11");
            strDate = strDate.replace("Dec", "12");
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy/HH:mm:ss", Locale.ENGLISH);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date date = sdf.parse(strDate);


            return date;
        }
        return null;
    }

    private boolean hasExpired(String dateOfResponse) {
        Date currdate = new Date();

        //SimpleDateFormat simpleDateFormat = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss");
        long second = 0;
        try {

            Date resdate = parseGMT(dateOfResponse);
            System.out.println("Date：" + resdate);
            System.out.println("当前时间：" + currdate);
            // 获得两个时间的毫秒时间差异
            long millisecond = currdate.getTime() - resdate.getTime();
            second = millisecond / (1000);
            System.out.println("时间间隔：" + second + "s");
        } catch (ParseException e) {
            e.printStackTrace();
        }
        if (second > HttpProxy.timetocheck) { 
            return true;
        } else {
            return false;
        }

    }
}


