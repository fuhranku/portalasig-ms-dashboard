package com.portalasig.ms.site.service;

import com.portalasig.ms.commons.rest.dto.Paginated;
import com.portalasig.ms.commons.rest.exception.ResourceNotFoundException;
import com.portalasig.ms.site.domain.entity.SemesterEntity;
import com.portalasig.ms.site.dto.semester.Semester;
import com.portalasig.ms.site.dto.semester.SemesterRequest;
import com.portalasig.ms.site.mapper.SemesterMapper;
import com.portalasig.ms.site.repository.SemesterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Slf4j
public class SemesterService {

    private final SemesterRepository semesterRepository;

    private final SemesterMapper semesterMapper;

    @Value("${site.semester.default-creation-status:false}")
    private Boolean isSemesterActiveByDefault;


    public Paginated<Semester> findAll(Pageable pageable) {
        Page<SemesterEntity> semesters = semesterRepository.findAll(pageable);
        if (semesters.isEmpty()) {
            throw new ResourceNotFoundException("No semesters found");
        }
        return Paginated.wrap(semesters.map(semesterMapper::toDto));
    }

    public Semester upsert(SemesterRequest request) {
        SemesterEntity semester;
        if (request.getSemesterId() == null) {
            semester = semesterMapper.toEntity(request);
            semester.setIsActive(isSemesterActiveByDefault);
            semesterRepository.getActiveSemester().ifPresent(
                    activeSemester -> {
                        activeSemester.setIsActive(false);
                        semesterRepository.save(activeSemester);
                        log.info("Semester semester_id={} has been deactivated", activeSemester.getSemesterId());
                    }
            );
        } else {
            semester = semesterRepository.findById(request.getSemesterId()).orElseThrow(
                    () -> new ResourceNotFoundException(
                            String.format("Semester with semester_id=%s not found", request.getSemesterId())
                    )
            );
            semesterMapper.toEntityFromExisting(request, semester);
        }
        semester = semesterRepository.save(semester);
        log.info("Semester with semester_id={} has been upserted", semester.getSemesterId());
        return semesterMapper.toDto(semester);
    }

    public void delete(Integer semesterId) {
        semesterRepository.findById(semesterId).orElseThrow(
                () -> new ResourceNotFoundException(String.format("Semester with semester_id=%s not found", semesterId))
        );
        try {
            semesterRepository.deleteById(semesterId);
        } catch (Exception e) {
            throw new ResourceNotFoundException(
                    String.format("Error deleting semester with semester_id=%s", semesterId)
            );
        }
    }

    public Semester findByAcademicPeriod(String academicPeriod) {
        SemesterEntity entity = semesterRepository.findByAcademicPeriod(academicPeriod).orElseThrow(
                () -> new ResourceNotFoundException(
                        String.format("Semester with academic_period=%s not found", academicPeriod)
                )
        );
        return semesterMapper.toDto(entity);
    }

    public Semester getActiveSemester() {
        SemesterEntity entity = semesterRepository.getActiveSemester().orElseThrow(
                () -> new ResourceNotFoundException("No active semester found")
        );
        return semesterMapper.toDto(entity);
    }
}
