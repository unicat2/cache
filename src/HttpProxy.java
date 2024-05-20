import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class HttpProxy {
    public static String cachePath="cache.txt";//缓存文件与程序存于同目录
    public static OutputStream writeCache;//缓存写

    public static int timetocheck=0;//用户设置时间

    //public static int TIMEOUT=500000;//response time out upper bound
    public static int RETRIEVE=5;//cache尝试与服务器进行连接的最大次数
    public static int CONNECT_PAUSE=5000;//waiting for connection

    public static void main(String[] args) throws IOException {

        ServerSocket serverSocket;
        Socket soket=null;

        //用户设置检查缓存的时间
        Scanner scanner = new Scanner(System.in);
        System.out.println("=============使用前请将浏览器代理配置为localhost，8888端口===============");
        System.out.println("请输入必须检查缓存一致性的时间间隔(单位：秒）");
        while(!(scanner.hasNextInt())) {
            System.out.println("您输入的不是整数，请输入一个整数！");
            scanner.next();										//继续输入
        }
        timetocheck = scanner.nextInt();

        writeCache=new FileOutputStream(cachePath,true);//缓存写对象

        System.out.println("Proxy start！");

        try {
            serverSocket=new ServerSocket(8888);//设置serversocket，绑定端口8888
            int i=0;

            while(true){

                soket=serverSocket.accept();
                i++;
                System.out.println("第"+i+"个请求到达");
                new MyProxy(soket);

            }
        } catch (IOException e) {
            if (soket != null) {
                soket.close();
            }
            e.printStackTrace();
        }

        writeCache.close();//关闭cache文件输出流
    }
}


