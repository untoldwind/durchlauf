package de.leanovate.durchlauf.target;

public class Main {
    public static void main(String[] args) {
        try {
            SampleHttpServer.start();
            while(true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
