package com.demo.pay;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ThirdGoddess
 * @version 1.0.0
 * @time 2022/12/22 14:37
 * @desc AliPay当面付Demo
 */
@RestController
@RequestMapping("pay")
public class AliPayController {

    //模拟一个用户的支付状态
    private boolean userPayState = false;

    //==================================================================================================================
    //这里都是固定的

    //支付宝网关地址
    private static final String SERVER_URL = "https://openapi.alipay.com/gateway.do";

    //charset
    private static final String CHARSET = "GBK";

    //format
    private static final String FORMAT = "json";

    //sign type
    private static final String SIGN_TYPE = "RSA2";

    //==================================================================================================================
    //下面这三个是需要配置的

    //APPID，即创建应用的那个ID,在应用详情中的左上角可以看到
    private static final String APPID = "**************";

    //应用私钥，注意是应用私钥！！！应用私钥！！！应用私钥！！！
    private static final String APP_PRIVATE_KEY = "**************";

    //支付宝公钥，注意是支付宝公钥！！！支付宝公钥！！！支付宝公钥！！！
    private static final String ALIPAY_PUBLIC_KEY = "**************";

    /**
     * 获取二维码
     * 获取的是用户要扫码支付的二维码
     * 创建订单，带入自己的业务逻辑
     */
    @RequestMapping(value = "/getQr", produces = MediaType.IMAGE_JPEG_VALUE)
    @ResponseBody
    public byte[] getQr() {

        userPayState = false;

        AlipayClient alipayClient = new DefaultAlipayClient(SERVER_URL, APPID, APP_PRIVATE_KEY, FORMAT, CHARSET, ALIPAY_PUBLIC_KEY, SIGN_TYPE);
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();

        //配置这是一个url，下图我已经配置好了，这个意思是当用户成功后，支付宝那边会调用这个地址url，他会给你传过去一些订单信息，
        //你处理完你的业务逻辑给支付宝响应success就行，就代表这个订单完成交易了！
        //* 建议前期开发的时候加上内网穿透调试，不然支付宝是没有办法调到你开发的接口的
        request.setNotifyUrl("http://**************.com/pay/payNotification");

        JSONObject bizContent = new JSONObject();

        //自己生成一个订单号，我这里直接用时间戳演示，正常情况下创建完订单需要存储到自己的业务数据库，做记录和支付完成后校验
        String orderNumber = "pay" + System.currentTimeMillis();

        bizContent.put("out_trade_no", orderNumber);//订单号
        bizContent.put("total_amount", 0.01);//订单金额
        bizContent.put("subject", "demo");//支付主题，自己稍微定义一下
        request.setBizContent(bizContent.toString());

        try {
            AlipayTradePrecreateResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                System.out.println("调用成功");
            } else {
                System.out.println("调用失败");
            }

            //获取生成的二维码，这里是一个String字符串，即二维码的内容；
            //然后用二维码生成SDK生成一下二维码，弄成图片返回给前端就行,我这里使用Zxing生成
            //其实也可以直接把这个字符串信息返回，让前端去生成，一样的道理，只需要关心这个二维码的内容就行
            String qrCode = response.getQrCode();

            //生成支付二维码图片
            BufferedImage image = QrCodeUtil.createImage(qrCode);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", out);
            byte[] b = out.toByteArray();
            out.write(b);
            out.close();

            //最终返回图片
            return b;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("调用失败");
        }
        return null;
    }

    /**
     * 支付完成后支付宝会请求这个回调
     */
    @PostMapping("payNotification")
    public String payNotification(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        Map<String, String[]> requestParams = request.getParameterMap();
        for (String name : requestParams.keySet()) {
            String[] values = requestParams.get(name);
            String valueStr = "";
            for (int i = 0; i < values.length; i++) {
                valueStr = (i == values.length - 1) ? valueStr + values[i] : valueStr + values[i] + ",";
            }
            params.put(name, valueStr);
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
        }

        //==============================================================================================================
        try {
            //执行验签，确保结果是支付宝回调的，而不是被恶意调用，一定要做这一步
            boolean signVerified = AlipaySignature.rsaCheckV1(params, ALIPAY_PUBLIC_KEY, CHARSET, SIGN_TYPE);
            if (signVerified) {
                //验签成功，继续执行业务逻辑
                System.out.println("验签成功");

                //再次主动查询订单，不要只依赖支付宝回调的结果
                String orderStatus = searchOrderStatus(params.get("out_trade_no"), params.get("trade_no"));
                switch (orderStatus) {
                    case "TRADE_SUCCESS"://交易支付成功；
                    case "TRADE_FINISHED": //交易结束，不可退款；
                        //TODO 在这里继续执行用户支付成功后的业务逻辑
                        userPayState = true;
                        break;
                }
                return "success";
            } else {
                //验签失败（很可能接口被非法调用）
                System.out.println("验签失败");
                return "fail";
            }
        } catch (AlipayApiException e) {
            e.printStackTrace();
            return "fail";
        }
    }

    /**
     * 封装一个订单查询
     *
     * @param outTradeNo 商户订单号
     * @param tradeNo    支付宝交易号。支付宝交易凭证号
     * @return 订单状态：String
     * @throws AlipayApiException AlipayApiException
     * @desc "WAIT_BUYER_PAY":交易创建，等待买家付款；"TRADE_CLOSED":未付款交易超时关闭，或支付完成后全额退款； "TRADE_SUCCESS":交易支付成功；"TRADE_FINISHED":交易结束，不可退款；
     */
    private String searchOrderStatus(String outTradeNo, String tradeNo) throws AlipayApiException {
        AlipayClient alipayClient = new DefaultAlipayClient(SERVER_URL, APPID, APP_PRIVATE_KEY, FORMAT, CHARSET, ALIPAY_PUBLIC_KEY, SIGN_TYPE); //获得初始化的AlipayClient
        AlipayTradeQueryRequest aliRequest = new AlipayTradeQueryRequest();//创建API对应的request类
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", outTradeNo);
        bizContent.put("trade_no", tradeNo);
        aliRequest.setBizContent(bizContent.toString()); //设置业务参数
        AlipayTradeQueryResponse response = alipayClient.execute(aliRequest);//通过alipayClient调用API，获得对应的response类
        JSONObject responseObject = JSONObject.parseObject(response.getBody());
        JSONObject alipayTradeQueryResponse = responseObject.getJSONObject("alipay_trade_query_response");
        return alipayTradeQueryResponse.getString("trade_status");
    }

    /**
     * 前端轮询查询这个接口，来查询订单的支付状态
     *
     * @return OrderStateEntity
     */
    @CrossOrigin
    @GetMapping("searchOrder")
    public OrderStateEntity searchOrder() {
        //userPayState是一个模拟值
        if (userPayState) {
            //用户支付成功了
            return new OrderStateEntity(200, "支付成功了");
        } else {
            //用户还没有支付
            return new OrderStateEntity(201, "你还没有支付哦");
        }
    }

    /**
     * 响应给前端的实体
     */
    static class OrderStateEntity {
        private int code;
        private String msg;

        public OrderStateEntity(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }
    }

}
