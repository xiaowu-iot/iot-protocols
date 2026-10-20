package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.AreaQuery;
import io.github.xiaowuiot.protocol.jt808.body.Auth;
import io.github.xiaowuiot.protocol.jt808.body.CanBusData;
import io.github.xiaowuiot.protocol.jt808.body.CompressedData;
import io.github.xiaowuiot.protocol.jt808.body.InfoService;
import io.github.xiaowuiot.protocol.jt808.body.LinkBodies;
import io.github.xiaowuiot.protocol.jt808.body.PhoneBodies;
import io.github.xiaowuiot.protocol.jt808.body.RsaPublicKey;
import io.github.xiaowuiot.protocol.jt808.body.StoredMultimedia;
import io.github.xiaowuiot.protocol.jt808.body.BodyDecoder;
import io.github.xiaowuiot.protocol.jt808.body.CircleArea;
import io.github.xiaowuiot.protocol.jt808.body.DriverInfo;
import io.github.xiaowuiot.protocol.jt808.body.GeneralResponse;
import io.github.xiaowuiot.protocol.jt808.body.InfoBodies;
import io.github.xiaowuiot.protocol.jt808.body.Location;
import io.github.xiaowuiot.protocol.jt808.body.LocationBatch;
import io.github.xiaowuiot.protocol.jt808.body.LocationQueryResponse;
import io.github.xiaowuiot.protocol.jt808.body.Multimedia;
import io.github.xiaowuiot.protocol.jt808.body.PassThrough;
import io.github.xiaowuiot.protocol.jt808.body.PolygonArea;
import io.github.xiaowuiot.protocol.jt808.body.Recorder;
import io.github.xiaowuiot.protocol.jt808.body.RectangleArea;
import io.github.xiaowuiot.protocol.jt808.body.Register;
import io.github.xiaowuiot.protocol.jt808.body.RegisterResponse;
import io.github.xiaowuiot.protocol.jt808.body.Route;
import io.github.xiaowuiot.protocol.jt808.body.SimpleBodies;
import io.github.xiaowuiot.protocol.jt808.body.TerminalAttributes;
import io.github.xiaowuiot.protocol.jt808.body.TerminalControl;
import io.github.xiaowuiot.protocol.jt808.body.TerminalParams;
import io.github.xiaowuiot.protocol.jt808.body.TextMessage;
import io.github.xiaowuiot.protocol.jt808.body.UpgradePackage;
import io.github.xiaowuiot.protocol.jt808.body.UpgradeResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息 ID → 消息体解码器。
 * <p>
 * 新增一种消息只需要在这里登记一行，解码器本身不用改。项目里要支持厂家的自定义消息，
 * 调用 {@link #register} 登记即可。
 * <p>
 * 平台下发的消息（0x8xxx）也登记了，因为模拟器作为终端需要解析它们。
 */
public final class BodyDecoders {

    private static final Map<Integer, BodyDecoder<?>> DECODERS = new ConcurrentHashMap<>();

    /**
     * 没有消息体的消息
     */
    private static final BodyDecoder<Object> EMPTY = (body, header) -> null;

    static {
        register(MessageId.TERMINAL_GENERAL_RESPONSE, GeneralResponse::decode);
        register(MessageId.PLATFORM_GENERAL_RESPONSE, GeneralResponse::decode);
        register(MessageId.HEARTBEAT, EMPTY);
        register(MessageId.LOGOUT, EMPTY);
        register(MessageId.REGISTER, Register::decode);
        register(MessageId.REGISTER_RESPONSE, RegisterResponse::decode);
        register(MessageId.AUTH, Auth::decode);
        register(MessageId.LOCATION, Location::decode);
        register(MessageId.LOCATION_BATCH, LocationBatch::decode);
        register(MessageId.LOCATION_QUERY, EMPTY);
        register(MessageId.LOCATION_QUERY_RESPONSE, LocationQueryResponse::decode);
        register(MessageId.SET_PARAMS, TerminalParams::decodeSet);
        register(MessageId.QUERY_PARAMS, EMPTY);
        register(MessageId.QUERY_PARAMS_RESPONSE, TerminalParams::decodeQueryResponse);
        register(MessageId.TERMINAL_CONTROL, TerminalControl::decode);
        register(MessageId.TEXT_MESSAGE, TextMessage::decode);
        register(MessageId.UPGRADE_PACKAGE, UpgradePackage::decode);
        register(MessageId.UPGRADE_RESULT, UpgradeResult::decode);
        register(MessageId.QUERY_PARAMS_BY_ID, SimpleBodies.QueryParamsById::decode);
        register(MessageId.QUERY_ATTRIBUTES, EMPTY);
        register(MessageId.ATTRIBUTES_RESPONSE, TerminalAttributes::decode);
        register(MessageId.TRACKING_CONTROL, SimpleBodies.TrackingControl::decode);
        register(MessageId.ALARM_CONFIRM, SimpleBodies.AlarmConfirm::decode);
        register(MessageId.MULTIMEDIA_EVENT, Multimedia.Event::decode);
        register(MessageId.MULTIMEDIA_DATA, Multimedia.Data::decode);
        register(MessageId.CAMERA_RESPONSE, Multimedia.CameraResponse::decode);
        register(MessageId.MULTIMEDIA_RESPONSE, Multimedia.DataResponse::decode);
        register(MessageId.CAMERA_COMMAND, Multimedia.CameraCommand::decode);
        register(MessageId.SET_CIRCLE_AREA, CircleArea::decode);
        register(MessageId.DELETE_CIRCLE_AREA, CircleArea.Delete::decode);
        register(MessageId.PASS_THROUGH_UP, PassThrough::decodeUplink);
        register(MessageId.PASS_THROUGH_DOWN, PassThrough::decodeDownlink);
        register(MessageId.SET_RECTANGLE_AREA, RectangleArea::decode);
        register(MessageId.DELETE_RECTANGLE_AREA, CircleArea.Delete::decode);
        register(MessageId.SET_POLYGON_AREA, PolygonArea::decode);
        register(MessageId.DELETE_POLYGON_AREA, CircleArea.Delete::decode);
        register(MessageId.SET_ROUTE, Route::decode);
        register(MessageId.DELETE_ROUTE, CircleArea.Delete::decode);
        register(MessageId.RECORDER_COMMAND, Recorder.Command::decode);
        register(MessageId.RECORDER_UPLOAD, Recorder.Upload::decode);
        register(MessageId.DRIVER_INFO_REQUEST, EMPTY);
        register(MessageId.DRIVER_INFO, DriverInfo::decode);
        register(MessageId.EVENT_SETTING, InfoBodies.EventSetting::decode);
        register(MessageId.EVENT_REPORT, InfoBodies.EventReport::decode);
        register(MessageId.QUESTION, InfoBodies.Question::decode);
        register(MessageId.QUESTION_ANSWER, InfoBodies.QuestionAnswer::decode);
        register(MessageId.VEHICLE_CONTROL, InfoBodies.VehicleControl::decode);
        register(MessageId.VEHICLE_CONTROL_RESPONSE, InfoBodies.VehicleControlResponse::decode);
        register(MessageId.QUERY_SERVER_TIME, EMPTY);
        register(MessageId.SERVER_TIME_RESPONSE, LinkBodies.ServerTime::decode);
        register(MessageId.PLATFORM_RESEND_REQUEST, LinkBodies.ResendRequest::decode);
        register(MessageId.TERMINAL_RESEND_REQUEST, LinkBodies.ResendRequest::decode);
        register(MessageId.LINK_CHECK, EMPTY);
        register(MessageId.CALL_BACK, PhoneBodies.CallBack::decode);
        register(MessageId.PHONE_BOOK, PhoneBodies.PhoneBook::decode);
        register(MessageId.INFO_MENU_SETTING, InfoService.MenuSetting::decode);
        register(MessageId.INFO_SUBSCRIBE, InfoService.Subscribe::decode);
        register(MessageId.INFO_SERVICE, InfoService.Content::decode);
        register(MessageId.AREA_QUERY, AreaQuery.Request::decode);
        register(MessageId.AREA_QUERY_RESPONSE, AreaQuery.Response::decode);
        register(MessageId.RECORDER_PARAMS, Recorder.Command::decode);
        register(MessageId.WAYBILL_UPLOAD, SimpleBodies.Waybill::decode);
        register(MessageId.CAN_BUS_DATA, CanBusData::decode);
        register(MessageId.STORED_MEDIA_QUERY, StoredMultimedia.Query::decode);
        register(MessageId.STORED_MEDIA_QUERY_RESPONSE, StoredMultimedia.QueryResponse::decode);
        register(MessageId.STORED_MEDIA_UPLOAD, StoredMultimedia.Upload::decode);
        register(MessageId.RECORD_AUDIO, StoredMultimedia.Record::decode);
        register(MessageId.STORED_MEDIA_UPLOAD_ONE, StoredMultimedia.UploadOne::decode);
        register(MessageId.COMPRESSED_DATA, CompressedData::decode);
        register(MessageId.TERMINAL_RSA_KEY, RsaPublicKey::decode);
        register(MessageId.PLATFORM_RSA_KEY, RsaPublicKey::decode);
    }

    private BodyDecoders() {
    }

    public static void register(int msgId, BodyDecoder<?> decoder) {
        DECODERS.put(msgId, decoder);
    }

    public static void unregister(int msgId) {
        DECODERS.remove(msgId);
    }

    /**
     * @return null 表示不认识这条消息
     */
    public static BodyDecoder<?> get(int msgId) {
        return DECODERS.get(msgId);
    }
}
