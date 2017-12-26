package com.xulog.demo.pay

import com.xulog.alipay.AliConfig
import com.xulog.alipay.AlipayF2fPay
import com.xulog.alipay.bean.misc.SignType
import com.xulog.alipay.bean.request.biz.Cancel
import com.xulog.alipay.bean.request.biz.PreCreate
import com.xulog.alipay.bean.request.biz.Query
import com.xulog.alipay.bean.request.biz.Refund
import com.xulog.alipay.util.MsicUtil
import net.glxn.qrgen.core.image.ImageType
import net.glxn.qrgen.javase.QRCode
import spark.Spark
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.*


class API {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val props = Properties()
            props.load(FileInputStream("application.properties"))
            val aliConfig = AliConfig(props["appId"]?.toString() ?: throw Exception(),
                    SignType.valueOf(props["method"]?.toString() ?: throw Exception()),//RSA RSA2
                    Files.readAllBytes(File("private_key").toPath() ?: throw Exception()).toString(Charsets.UTF_8),
                    Files.readAllBytes(File("public_key").toPath() ?: throw Exception()).toString(Charsets.UTF_8),
                    props["callbackUrl"]?.toString() ?: throw Exception())
            val alipayF2fPay = AlipayF2fPay(aliConfig)

            Spark.port(props["port"]?.toString()?.toIntOrNull() ?: 8090)

            Spark.get("qr/:amount") { req, res ->
                val size = req.queryParams("size")?.toInt() ?: 800
                val money = req.params(":money") ?: "1000"
                val randomId = MsicUtil.randomString(16)
                val pre = alipayF2fPay.execute(PreCreate(randomId, money, "打赏:$randomId"))
                res.type("image/png")
                QRCode.from(pre.biz_content.qr_code)
                        .to(ImageType.PNG)
                        .withSize(size, size)
                        .withCharset(StandardCharsets.UTF_8.displayName())
                        .writeTo(res.raw().outputStream)
            }

            Spark.get("pay/:amount") { req, _ ->
                val amount = req.params(":amount")
                val randomId = MsicUtil.randomString(16)
                val pre = alipayF2fPay.execute(PreCreate(randomId, amount, "测试单:$randomId"))
                val byteArrayOutputStream = ByteArrayOutputStream()
                QRCode.from(pre.biz_content.qr_code)
                        .to(ImageType.PNG)
                        .withSize(800, 800)
                        .withCharset(StandardCharsets.UTF_8.displayName())
                        .writeTo(byteArrayOutputStream)
                val base64Str = Base64.getEncoder()
                        .encodeToString(byteArrayOutputStream.toByteArray())
                """
                    <html>
                        <head><title>测试单:$randomId</title></head>
                        <body>
                        <p>orderId:$randomId</p>
                        <p>amount:$amount 元</p>
                        <img src="data:image/png;base64,$base64Str"/>
                        <a href="../detail/$randomId">detail</a>
                        </body>
                    </html>
                """
            }

            Spark.get("detail/:otn") { req, _ ->
                val s = req.params(":otn")
                val ofOutTradeNo = Query.ofOutTradeNo(s)
                val execute = alipayF2fPay.execute(ofOutTradeNo)
                val prettyPrinter = alipayF2fPay.objectMapper.writer().withDefaultPrettyPrinter()
                """
                    <html>
                        <body>
                            <pre>${prettyPrinter.writeValueAsString(execute)}</pre>
                            <br>
                            <a href="../close/$s">close</a>
                            <a href="../refund/$s/${execute.biz_content.total_amount}">refund</a>
                        </body>
                    </html>
                """
            }

            Spark.get("close/:otn") { req, res ->
                val s = req.params(":otn")
                val ofOutTradeNo = Cancel.ofOutTradeNo(s)
                val execute = alipayF2fPay.execute(ofOutTradeNo)
                res.redirect("../detail/" + s)
            }

            Spark.get("refund/:otn/:amount") { req, res ->
                val s = req.params(":otn")
                val amount = req.params(":amount")
                val ofOutTradeNo = Refund.ofOutTradeNo(s, amount.toInt())
                val execute = alipayF2fPay.execute(ofOutTradeNo)
                res.redirect("../../detail/" + s)
            }

            Spark.post("notify") { req, _ ->
                try {
                    val map = req.queryMap().toMap().map { it.key to it.value.first() }.toMap()
                    val callBack = alipayF2fPay.callBack(map)
                    println(alipayF2fPay.objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(callBack))
                    "success"
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}