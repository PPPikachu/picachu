package com.cafababe.exec;

import com.cafababe.exec.handler.DataHandler;
import com.cafababe.exec.handler.DataHandlerChain;
import com.cafababe.exec.handler.ExceptionHandler;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteResultHandler;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <dt>data handler</dt>
 * <p>
 * <dd>可以不设置DataHandler，默认情况下使用{@link DataHandlerChain.VoidDataHandler}，如果只设置一个DataHandler，
 * 那么不会创建{@link DataHandlerChain}对象。如果设置超过一个DataHandler,会将传入的DataHandler存储在{@link DataHandlerChain}。
 * 存在一种特殊的只处理异常的DataHandler{@link ExceptionHandler}，只需要实现ExceptonHandler并且
 * 将其注册，ExecStarter将会处理异常。如果未注册ExceptionHandler，异常将会抛弃，不进行任何处理。<dd/>
 * <p>
 * <dt>timeout</dt>
 * <dd>如果命令执行时间超过timeout，连接将中断</dd>
 *
 * <dt>shutdown ExecStarter<dt/>
 * <dd>如果需要关闭ExecStarter，直接调用{@code shutdown}。在多线程中调用，可能导致ExecStarter已经关闭，但是commamd未执行，未执行的
 * command将抛出{@link RejectedExecutionException}异常，但是可以通过实现@{link {@link ExceptionHandler}}接口进行自定义的处理<dd/>
 *
 *
 * @author cafababe
 * @since 1.0
 */
public class ExecStarter {

    private static ExecutorService readPool = new ThreadPoolExecutor(
            5, 20,
            30, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(256), new AgainPolicy());

    private static ExecutorService workPool = new ThreadPoolExecutor(
            5, 20,
            30, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(256), new AgainPolicy());

    /**
     * 启动执行命令
     *
     * @param command      命令行
     * @param dataHandlers 连接超时时间
     * @return
     * @throws IOException
     */
    public ExecuteInfo start(String command, DataHandler... dataHandlers) throws IOException {

        CommandLine commandLine = CommandLine.parse(command);
        ExecuteInfo info = getDefaultHandler();

        info.getThread().setCommand(command);

        runtime(info, commandLine, dataHandlers);

        return info;
    }

    /**
     * 启动执行命令
     *
     * @param command      命令行
     * @param timeout      连接超时时间
     * @param dataHandlers 数据处理对象
     * @return
     * @throws IOException
     */
    public ExecuteInfo start(String command, long timeout, DataHandler... dataHandlers) throws IOException {

        CommandLine commandLine = CommandLine.parse(command);
        ExecuteInfo info = getDefaultHandler(timeout);

        info.getThread().setCommand(command);

        runtime(info, commandLine, dataHandlers);

        return info;
    }

    /**
     * 执行命令
     *
     * @param executeInfo  exec运行需要的信息
     * @param commandLine  执行的命令行
     * @param dataHandlers 数据处理对象
     * @throws IOException
     * @see org.apache.commons.exec.Executor#execute(CommandLine, java.util.Map, ExecuteResultHandler)
     */
    private void runtime(ExecuteInfo executeInfo, CommandLine commandLine, DataHandler... dataHandlers) throws IOException {

        executeInfo.getThread().setResultHandler(executeInfo.getResultHandler());
        // 多个handler使用chain，没有dataHandler的使用默认的dataHandler
        DataHandler dataHandler = dataHandlers.length > 1 ? new DataHandlerChain(dataHandlers) :
                dataHandlers.length == 0 ? DataHandlerChain.getDefaultDataHandler() : dataHandlers[0];

        executeInfo.getThread().setDataHandler(dataHandler);

        workPool.execute(new ExecuteThread(commandLine, executeInfo.getResultHandler(), executeInfo));

        readPool.execute(executeInfo.getThread());
    }

    /**
     * 默认的单次连接超时时间为30分钟
     *
     * @return
     */
    private ExecuteInfo getDefaultHandler() {
        return getDefaultHandler(1800000);
    }

    /**
     * 可设置timeout
     *
     * @param timeout 超时时间
     * @return
     */
    private ExecuteInfo getDefaultHandler(long timeout) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
        // 创建读取线程
        ReaderThread readerThread = new ReaderThread(outputStream);
        // 超过30分钟，连接自动断开
        ExecuteWatchdog watchdog = new ExecuteWatchdog(timeout);

        return new ExecuteInfo(readerThread, streamHandler, watchdog);
    }

    /**
     * 关闭ExecStarter
     */
    public void shutdown() {
        if (readPool != null) {
            readPool.shutdown();
        }

        if (workPool != null) {
            workPool.shutdown();
        }
    }
}
