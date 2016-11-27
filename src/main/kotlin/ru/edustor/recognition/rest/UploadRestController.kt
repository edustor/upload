package ru.edustor.recognition.rest

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.edustor.recognition.exception.InvalidContentTypeException
import ru.edustor.recognition.service.FileStorageService
import java.util.*

@RestController
@RequestMapping("api/v1/upload")
class UploadRestController(val storage: FileStorageService, val rabbitTemplate: RabbitTemplate) {
    @RequestMapping("pages", method = arrayOf(RequestMethod.POST))
    fun handlePdfUpload(@RequestParam("file") file: MultipartFile) {

        if (file.contentType != "application/pdf") {
            throw InvalidContentTypeException("This url is accepts only application/pdf documents")
        }

        val uuid = UUID.randomUUID().toString()
        storage.putPdf(uuid, file.inputStream, file.size)
    }
}