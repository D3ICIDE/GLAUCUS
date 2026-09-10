import core.Orchestration;
import core.Server;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Server through Main ");
        new Server(new Orchestration()).start(8080);
    }
}
