package ru.edustor.upload.rest

import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.edustor.commons.auth.assertScopeContains
import ru.edustor.commons.models.internal.accounts.EdustorAccount
import ru.edustor.commons.models.internal.processing.pdf.PdfUploadedEvent
import ru.edustor.commons.models.upload.UploadResult
import ru.edustor.commons.storage.service.BinaryObjectStorageService
import ru.edustor.commons.storage.service.BinaryObjectStorageService.ObjectType
import ru.edustor.upload.exception.InvalidContentTypeException
import ru.edustor.upload.exception.MaxFileSizeViolationException
import java.io.InputStream
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("api/v1/upload")
class UploadRestController(val storage: BinaryObjectStorageService, val rabbitTemplate: RabbitTemplate) {
    val logger: Logger = LoggerFactory.getLogger(UploadRestController::class.java)
    val httpClient = OkHttpClient()

    @RequestMapping("pages", method = arrayOf(RequestMethod.POST))
    fun handlePdfUpload(@RequestParam("file") file: MultipartFile,
                        @RequestParam("target_lesson", required = false) targetLessonId: String?,
                        account: EdustorAccount): UploadResult? {

        account.assertScopeContains("upload2", "offline2")

        if (file.contentType != "application/pdf") {
            throw InvalidContentTypeException("This url is accepts only application/pdf documents")
        }

        val uploadUuid = processFile(account.uuid, file.inputStream, file.size)

        val uploadedEvent = PdfUploadedEvent(uploadUuid, account.uuid, Instant.now(), targetLessonId)
        rabbitTemplate.convertAndSend("internal.edustor", "uploaded.pdf.pages.processing", uploadedEvent)

        val result = UploadResult(uploadUuid)
        return result
    }

    @RequestMapping("pages/url", method = arrayOf(RequestMethod.POST))
    fun handleUrlPdfUpload(@RequestParam url: String,
                           @RequestParam("target_lesson", required = false) targetLessonId: String?,
                           @RequestParam("uploader_id", required = false) requestedUploaderId: String?, // For internal usage
                           account: EdustorAccount): UploadResult {
        val uploaderId = if (requestedUploaderId != null) {
            account.assertScopeContains("internal")
            requestedUploaderId
        } else {
            account.assertScopeContains("upload")
            account.uuid
        }

        val req = Request.Builder().url(url).build()
        val resp = httpClient.newCall(req).execute()

        resp.body().use {
            if (resp.body().contentType().toString() != "application/pdf") {
                throw InvalidContentTypeException("This url is accepts only application/pdf documents")
            }

            val contentLength = resp.body().contentLength()
            if (contentLength > 100 * 1024 * 1024) {
                throw MaxFileSizeViolationException()
            } else if (contentLength == -1L) {
                throw MaxFileSizeViolationException("URL's server didn't return Content-Length")
            }

            val uploadUuid = processFile(uploaderId, resp.body().byteStream(), contentLength)
            val result = UploadResult(uploadUuid)
            return result
        }
    }

    private fun processFile(uploaderId: String, file: InputStream, fileSize: Long): String {
        val uuid = UUID.randomUUID().toString()
        storage.put(ObjectType.PDF_UPLOAD, uuid, file, fileSize)
        logger.info("PDF $uuid uploaded by $uploaderId")
        return uuid
    }
}