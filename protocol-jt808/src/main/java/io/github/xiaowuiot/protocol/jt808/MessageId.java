package io.github.xiaowuiot.protocol.jt808;

/**
 * 消息 ID。终端发给平台的以 0 开头，平台发给终端的以 8 开头。
 * <p>
 * 注释里标了版本的，只在那个版本里有：2019 版新增了查询服务器时间、终端补传分包请求、链路检测、查询区域；
 * 删掉了信息点播（0x0303 / 0x8303 / 0x8304）。
 */
public final class MessageId {

    // ---------- 终端 → 平台 ----------
    public static final int TERMINAL_GENERAL_RESPONSE = 0x0001;
    public static final int HEARTBEAT = 0x0002;
    public static final int LOGOUT = 0x0003;
    /** 2019 */
    public static final int QUERY_SERVER_TIME = 0x0004;
    /** 2019 */
    public static final int TERMINAL_RESEND_REQUEST = 0x0005;
    public static final int REGISTER = 0x0100;
    public static final int AUTH = 0x0102;
    public static final int QUERY_PARAMS_RESPONSE = 0x0104;
    public static final int ATTRIBUTES_RESPONSE = 0x0107;
    public static final int UPGRADE_RESULT = 0x0108;
    public static final int LOCATION = 0x0200;
    public static final int LOCATION_QUERY_RESPONSE = 0x0201;
    public static final int EVENT_REPORT = 0x0301;
    public static final int QUESTION_ANSWER = 0x0302;
    /** 2011/2013 */
    public static final int INFO_SUBSCRIBE = 0x0303;
    public static final int VEHICLE_CONTROL_RESPONSE = 0x0500;
    /** 2019 */
    public static final int AREA_QUERY_RESPONSE = 0x0608;
    public static final int RECORDER_UPLOAD = 0x0700;
    public static final int WAYBILL_UPLOAD = 0x0701;
    public static final int DRIVER_INFO = 0x0702;
    public static final int LOCATION_BATCH = 0x0704;
    public static final int CAN_BUS_DATA = 0x0705;
    public static final int MULTIMEDIA_EVENT = 0x0800;
    public static final int MULTIMEDIA_DATA = 0x0801;
    public static final int STORED_MEDIA_QUERY_RESPONSE = 0x0802;
    public static final int CAMERA_RESPONSE = 0x0805;
    public static final int PASS_THROUGH_UP = 0x0900;
    public static final int COMPRESSED_DATA = 0x0901;
    public static final int TERMINAL_RSA_KEY = 0x0A00;

    // ---------- 平台 → 终端 ----------
    public static final int PLATFORM_GENERAL_RESPONSE = 0x8001;
    public static final int PLATFORM_RESEND_REQUEST = 0x8003;
    /** 2019 */
    public static final int SERVER_TIME_RESPONSE = 0x8004;
    public static final int REGISTER_RESPONSE = 0x8100;
    public static final int SET_PARAMS = 0x8103;
    public static final int QUERY_PARAMS = 0x8104;
    public static final int TERMINAL_CONTROL = 0x8105;
    public static final int QUERY_PARAMS_BY_ID = 0x8106;
    public static final int QUERY_ATTRIBUTES = 0x8107;
    public static final int UPGRADE_PACKAGE = 0x8108;
    public static final int LOCATION_QUERY = 0x8201;
    public static final int TRACKING_CONTROL = 0x8202;
    public static final int ALARM_CONFIRM = 0x8203;
    /** 2019 */
    public static final int LINK_CHECK = 0x8204;
    public static final int TEXT_MESSAGE = 0x8300;
    public static final int EVENT_SETTING = 0x8301;
    public static final int QUESTION = 0x8302;
    /** 2011/2013 */
    public static final int INFO_MENU_SETTING = 0x8303;
    /** 2011/2013 */
    public static final int INFO_SERVICE = 0x8304;
    public static final int CALL_BACK = 0x8400;
    public static final int PHONE_BOOK = 0x8401;
    public static final int VEHICLE_CONTROL = 0x8500;
    public static final int SET_CIRCLE_AREA = 0x8600;
    public static final int DELETE_CIRCLE_AREA = 0x8601;
    public static final int SET_RECTANGLE_AREA = 0x8602;
    public static final int DELETE_RECTANGLE_AREA = 0x8603;
    public static final int SET_POLYGON_AREA = 0x8604;
    public static final int DELETE_POLYGON_AREA = 0x8605;
    public static final int SET_ROUTE = 0x8606;
    public static final int DELETE_ROUTE = 0x8607;
    /** 2019 */
    public static final int AREA_QUERY = 0x8608;
    public static final int RECORDER_COMMAND = 0x8700;
    public static final int RECORDER_PARAMS = 0x8701;
    public static final int DRIVER_INFO_REQUEST = 0x8702;
    public static final int MULTIMEDIA_RESPONSE = 0x8800;
    public static final int CAMERA_COMMAND = 0x8801;
    public static final int STORED_MEDIA_QUERY = 0x8802;
    public static final int STORED_MEDIA_UPLOAD = 0x8803;
    public static final int RECORD_AUDIO = 0x8804;
    public static final int STORED_MEDIA_UPLOAD_ONE = 0x8805;
    public static final int PASS_THROUGH_DOWN = 0x8900;
    public static final int PLATFORM_RSA_KEY = 0x8A00;

    private MessageId() {
    }

    /**
     * 消息名称，日志和报文监视用
     */
    public static String name(int msgId) {
        switch (msgId) {
            case TERMINAL_GENERAL_RESPONSE: return "终端通用应答";
            case HEARTBEAT: return "终端心跳";
            case LOGOUT: return "终端注销";
            case QUERY_SERVER_TIME: return "查询服务器时间请求";
            case TERMINAL_RESEND_REQUEST: return "终端补传分包请求";
            case REGISTER: return "终端注册";
            case AUTH: return "终端鉴权";
            case QUERY_PARAMS_RESPONSE: return "查询终端参数应答";
            case ATTRIBUTES_RESPONSE: return "查询终端属性应答";
            case UPGRADE_RESULT: return "终端升级结果通知";
            case LOCATION: return "位置信息汇报";
            case LOCATION_QUERY_RESPONSE: return "位置信息查询应答";
            case EVENT_REPORT: return "事件报告";
            case QUESTION_ANSWER: return "提问应答";
            case INFO_SUBSCRIBE: return "信息点播/取消";
            case VEHICLE_CONTROL_RESPONSE: return "车辆控制应答";
            case AREA_QUERY_RESPONSE: return "查询区域或线路数据应答";
            case RECORDER_UPLOAD: return "行驶记录数据上传";
            case WAYBILL_UPLOAD: return "电子运单上报";
            case DRIVER_INFO: return "驾驶员身份信息采集上报";
            case LOCATION_BATCH: return "定位数据批量上传";
            case CAN_BUS_DATA: return "CAN 总线数据上传";
            case MULTIMEDIA_EVENT: return "多媒体事件信息上传";
            case MULTIMEDIA_DATA: return "多媒体数据上传";
            case STORED_MEDIA_QUERY_RESPONSE: return "存储多媒体数据检索应答";
            case CAMERA_RESPONSE: return "摄像头立即拍摄命令应答";
            case PASS_THROUGH_UP: return "数据上行透传";
            case COMPRESSED_DATA: return "数据压缩上报";
            case TERMINAL_RSA_KEY: return "终端 RSA 公钥";
            case PLATFORM_GENERAL_RESPONSE: return "平台通用应答";
            case PLATFORM_RESEND_REQUEST: return "补传分包请求";
            case SERVER_TIME_RESPONSE: return "查询服务器时间应答";
            case REGISTER_RESPONSE: return "终端注册应答";
            case SET_PARAMS: return "设置终端参数";
            case QUERY_PARAMS: return "查询终端参数";
            case TERMINAL_CONTROL: return "终端控制";
            case QUERY_PARAMS_BY_ID: return "查询指定终端参数";
            case QUERY_ATTRIBUTES: return "查询终端属性";
            case UPGRADE_PACKAGE: return "下发终端升级包";
            case LOCATION_QUERY: return "位置信息查询";
            case TRACKING_CONTROL: return "临时位置跟踪控制";
            case ALARM_CONFIRM: return "人工确认报警消息";
            case LINK_CHECK: return "链路检测";
            case TEXT_MESSAGE: return "文本信息下发";
            case EVENT_SETTING: return "事件设置";
            case QUESTION: return "提问下发";
            case INFO_MENU_SETTING: return "信息点播菜单设置";
            case INFO_SERVICE: return "信息服务";
            case CALL_BACK: return "电话回拨";
            case PHONE_BOOK: return "设置电话本";
            case VEHICLE_CONTROL: return "车辆控制";
            case SET_CIRCLE_AREA: return "设置圆形区域";
            case DELETE_CIRCLE_AREA: return "删除圆形区域";
            case SET_RECTANGLE_AREA: return "设置矩形区域";
            case DELETE_RECTANGLE_AREA: return "删除矩形区域";
            case SET_POLYGON_AREA: return "设置多边形区域";
            case DELETE_POLYGON_AREA: return "删除多边形区域";
            case SET_ROUTE: return "设置路线";
            case DELETE_ROUTE: return "删除路线";
            case AREA_QUERY: return "查询区域或线路数据";
            case RECORDER_COMMAND: return "行驶记录数据采集命令";
            case RECORDER_PARAMS: return "行驶记录参数下传命令";
            case DRIVER_INFO_REQUEST: return "上报驾驶员身份信息请求";
            case MULTIMEDIA_RESPONSE: return "多媒体数据上传应答";
            case CAMERA_COMMAND: return "摄像头立即拍摄命令";
            case STORED_MEDIA_QUERY: return "存储多媒体数据检索";
            case STORED_MEDIA_UPLOAD: return "存储多媒体数据上传";
            case RECORD_AUDIO: return "录音开始命令";
            case STORED_MEDIA_UPLOAD_ONE: return "单条存储多媒体数据检索上传";
            case PASS_THROUGH_DOWN: return "数据下行透传";
            case PLATFORM_RSA_KEY: return "平台 RSA 公钥";
            default:
                String ext = EXTRA_NAMES.get(msgId);
                return ext != null ? ext : String.format("0x%04X", msgId);
        }
    }

    private static final java.util.Map<Integer, String> EXTRA_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 扩展协议（1078、主动安全）登记消息名称，日志和下发记录里显示中文名
     */
    public static void registerName(int msgId, String name) {
        EXTRA_NAMES.put(msgId, name);
    }
}
