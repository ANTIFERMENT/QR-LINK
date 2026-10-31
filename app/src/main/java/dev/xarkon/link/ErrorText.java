package dev.xarkon.link;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-readable Russian messages for the most common Android/network errors. */
public final class ErrorText {
    private ErrorText(){}
    private static final Pattern HTTP=Pattern.compile("(?i)\\bHTTP\\s*(\\d{3})\\b");
    public static String human(Throwable error){
        return human(error==null?null:error.getMessage());
    }
    public static String human(String message){
        if(message==null||message.trim().isEmpty())
            return "Не удалось выполнить операцию. Повторите попытку.";
        String lower=message.toLowerCase(Locale.ROOT);
        if(lower.contains("unknownhost")||lower.contains("unable to resolve host")||
           lower.contains("no address associated with hostname")||
           lower.contains("name or service not known"))
            return "Не удалось определить адрес устройства или сервера. Проверьте подключение и DNS.";
        if(lower.contains("connection refused")||lower.contains("econnrefused"))
            return "Получатель отклонил соединение. Откройте QR LINK на принимающем устройстве.";
        if(lower.contains("no route to host")||lower.contains("network is unreachable")||
           lower.contains("enetunreach")||lower.contains("ehostunreach"))
            return "Нет маршрута до получателя. Проверьте Wi-Fi и соединение между устройствами.";
        if(lower.contains("sockettimeout")||lower.contains("timed out")||
           lower.contains("timeout")||lower.contains("etimedout"))
            return "Превышено время ожидания. Убедитесь, что получатель находится рядом и готов к передаче.";
        if(lower.contains("broken pipe")||lower.contains("connection reset")||
           lower.contains("econnreset"))
            return "Соединение прервалось. Непереданные файлы можно отправить повторно.";
        if(lower.contains("permission denied")||lower.contains("securityexception")||
           lower.contains("eacces"))
            return "Не хватает разрешений Android. Проверьте доступ к файлам и устройствам поблизости.";
        if(lower.contains("no space left")||lower.contains("enospc"))
            return "Недостаточно свободного места на устройстве.";
        if(lower.contains("file not found")||lower.contains("enoent"))
            return "Исходный файл недоступен или был перемещён.";
        if(lower.contains("unable to open")||lower.contains("failed to open"))
            return "Не удалось открыть файл. Проверьте доступ и повторите попытку.";
        Matcher http=HTTP.matcher(message);
        if(http.find()&&!message.matches(".*[А-Яа-яЁё].*"))
            return "Сервер ответил ошибкой "+http.group(1)+". Повторите попытку.";
        // Preserve existing informative Russian messages, but never expose
        // unlocalized low-level exception dumps to end users.
        if(message.matches(".*[А-Яа-яЁё].*")){
            if(lower.contains("java.")||lower.contains("exception:")||
               lower.contains("android.")||lower.contains("libcore."))
                return "Не удалось выполнить операцию. Проверьте соединение и разрешения Android.";
            return message;
        }
        return "Не удалось выполнить операцию. Проверьте подключение и повторите попытку.";
    }
}
