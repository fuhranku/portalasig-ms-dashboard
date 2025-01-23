package com.portalasig.ms.site.service;

import com.opencsv.CSVReader;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.portalasig.ms.commons.rest.dto.Paginated;
import com.portalasig.ms.commons.rest.exception.BadRequestException;
import com.portalasig.ms.commons.rest.exception.ConflictException;
import com.portalasig.ms.commons.rest.exception.ResourceNotFoundException;
import com.portalasig.ms.commons.rest.exception.SystemErrorException;
import com.portalasig.ms.site.domain.entity.CareerEntity;
import com.portalasig.ms.site.domain.entity.ClassificationEntity;
import com.portalasig.ms.site.domain.entity.CourseEntity;
import com.portalasig.ms.site.dto.course.Course;
import com.portalasig.ms.site.dto.course.CourseRequest;
import com.portalasig.ms.site.dto.course.CsvCourse;
import com.portalasig.ms.site.mapper.CourseMapper;
import com.portalasig.ms.site.repository.CareerRepository;
import com.portalasig.ms.site.repository.ClassificationRepository;
import com.portalasig.ms.site.repository.CourseRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@RequiredArgsConstructor
@Service
@Slf4j
public class CourseService {

    private final CourseRepository courseRepository;

    private final CareerRepository careerRepository;

    private final ClassificationRepository classificationRepository;

    private final CourseMapper courseMapper;

    @Value("${site.tools.courses.csv.input-header}")
    private final HashSet<String> inputCsvHeader;

    public Paginated<Course> findAll(Pageable pageable) {
        Page<CourseEntity> courses = courseRepository.findAll(pageable);
        if (courses.isEmpty()) {
            throw new ResourceNotFoundException("No courses found");
        }
        return Paginated.wrap(courses.map(courseMapper::toDto));
    }

    @Transactional
    public Course upsert(CourseRequest request) {
        CourseEntity course;
        List<CareerEntity> careers = careerRepository.findAllById(request.getCareers());
        List<ClassificationEntity> classifications = classificationRepository.findAllById(request.getClassifications());
        if (careers.isEmpty() || classifications.isEmpty()) {
            throw new ResourceNotFoundException("No careers or classifications found. Skipping Course upsert");
        }
        if (request.getCourseId() == null) {
            if (courseRepository.findByCode(request.getCode()).isPresent()) {
                throw new ConflictException(
                        String.format("Course with code=%s already exists. Skipping Course creation", request.getCode())
                );
            }
            course = courseMapper.toEntity(request);
            course.setCareers(new HashSet<>(careers));
            course.setClassifications(new HashSet<>(classifications));
        } else {
            course = courseRepository.findById(request.getCourseId()).orElseThrow(
                    () -> new ResourceNotFoundException(
                            String.format("Course with course_id=%s not found. Skipping update", request.getCourseId())
                    )
            );
            course.getCareers().clear();
            course.getClassifications().clear();
            course.setCareers(new HashSet<>(careers));
            course.setClassifications(new HashSet<>(classifications));
            courseMapper.toEntityFromExisting(course, request);
        }

        course = courseRepository.save(course);
        return courseMapper.toDto(course);
    }

    @Transactional
    public void delete(Integer courseId) {
        courseRepository.findById(courseId).orElseThrow(
                () -> new ResourceNotFoundException(
                        String.format("Course with course_id=%s not found", courseId)
                )
        );
        try {
            courseRepository.deleteById(courseId);
        } catch (Exception e) {
            log.error("Error deleting course with course_id={}", courseId, e);
            throw new ResourceNotFoundException(
                    String.format("Error deleting course with course_id=%s", courseId)
            );
        }
    }

    public void importCoursesFromCsv(InputStream stream) {
        try (CSVReader reader = new CSVReader(new InputStreamReader(stream))) {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            // readNext() method reads the line and skips it from the array
            String[] header = reader.readNext();
            validateHeader(Arrays.asList(header));
            List<CsvCourse> csvCourses = new CsvToBeanBuilder<CsvCourse>(reader)
                    .withType(CsvCourse.class)
                    .build()
                    .parse();
            log.info("Starting courses import from csv with courses_size={}", csvCourses.size());
            List<CourseEntity> courseEntities = csvCourses.stream().map(this::toEntityFromCsv).toList();
            courseRepository.saveAll(courseEntities);
            stopWatch.stop();
            log.info("Import courses from csv finished in {}ms", stopWatch.getTotalTimeMillis());
        } catch (CsvValidationException | IOException e) {
            throw new SystemErrorException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Something went wrong while parsing csv file", e);
        }
    }

    private CourseEntity toEntityFromCsv(CsvCourse csvCourse) {
        CourseEntity entity = courseMapper.toEntityFromCsv(csvCourse);
        entity.setCareers(new HashSet<>(getCareers(csvCourse)));
        entity.setClassifications(new HashSet<>(getClassifications(csvCourse)));
        return entity;
    }

    private List<ClassificationEntity> getClassifications(CsvCourse csvCourse) {
        List<ClassificationEntity> classifications = new ArrayList<>();
        try {
            if (csvCourse.getClassifications() != null) {
                classifications = classificationRepository.findAllById(csvCourse.getClassifications());
            }
        } catch (Exception e) {
            log.error(
                    "Error when fetching classification for course_id={}, skipping",
                    csvCourse.getCode(),
                    e
            );
        }
        return classifications;
    }

    private List<CareerEntity> getCareers(CsvCourse csvCourse) {
        List<CareerEntity> careers = new ArrayList<>();
        try {
            if (csvCourse.getCareers() != null) {
                careers = careerRepository.findAllById(csvCourse.getCareers());
            }
        } catch (Exception e) {
            log.error(
                    "Error when fetching career  for course_id={}, skipping",
                    csvCourse.getCode(),
                    e
            );
        }
        return careers;
    }

    private void validateHeader(List<String> fileHeader) {
        HashSet<String> fileHeaderSet = new HashSet<>(fileHeader);
        if (!fileHeaderSet.containsAll(inputCsvHeader)) {
            throw new BadRequestException("Invalid csv header");
        }
    }

    public Course findByCode(String courseCode) {
        return courseRepository.findByCode(courseCode)
                .map(courseMapper::toDto)
                .orElseThrow(() ->
                        new ResourceNotFoundException(String.format("Course with code=%s not found", courseCode))
                );
    }
}
