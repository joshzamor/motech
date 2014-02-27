package org.motechproject.mds.service.impl.internal;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.motechproject.mds.builder.MDSConstructor;
import org.motechproject.mds.domain.Entity;
import org.motechproject.mds.domain.EntityDraft;
import org.motechproject.mds.domain.Field;
import org.motechproject.mds.domain.FieldMetadata;
import org.motechproject.mds.domain.FieldSetting;
import org.motechproject.mds.domain.FieldValidation;
import org.motechproject.mds.domain.Lookup;
import org.motechproject.mds.domain.Type;
import org.motechproject.mds.domain.TypeSetting;
import org.motechproject.mds.domain.TypeValidation;
import org.motechproject.mds.dto.AdvancedSettingsDto;
import org.motechproject.mds.dto.DraftResult;
import org.motechproject.mds.dto.EntityDto;
import org.motechproject.mds.dto.FieldBasicDto;
import org.motechproject.mds.dto.FieldDto;
import org.motechproject.mds.dto.FieldInstanceDto;
import org.motechproject.mds.dto.FieldValidationDto;
import org.motechproject.mds.dto.LookupDto;
import org.motechproject.mds.dto.SettingDto;
import org.motechproject.mds.dto.ValidationCriterionDto;
import org.motechproject.mds.ex.EntityAlreadyExistException;
import org.motechproject.mds.ex.EntityChangedException;
import org.motechproject.mds.ex.EntityNotFoundException;
import org.motechproject.mds.ex.EntityReadOnlyException;
import org.motechproject.mds.ex.FieldNotFoundException;
import org.motechproject.mds.ex.NoSuchTypeException;
import org.motechproject.mds.repository.AllEntities;
import org.motechproject.mds.repository.AllEntityAudits;
import org.motechproject.mds.repository.AllEntityDrafts;
import org.motechproject.mds.repository.AllTypes;
import org.motechproject.mds.service.BaseMdsService;
import org.motechproject.mds.service.EntityService;
import org.motechproject.mds.util.ClassName;
import org.motechproject.mds.util.Constants;
import org.motechproject.mds.util.FieldHelper;
import org.motechproject.mds.util.SecurityMode;
import org.motechproject.mds.web.DraftData;
import org.motechproject.mds.web.ExampleData;
import org.motechproject.mds.web.domain.EntityRecord;
import org.motechproject.mds.web.domain.HistoryRecord;
import org.motechproject.mds.web.domain.PreviousRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.motechproject.mds.util.Constants.Util.TRUE;

/**
 * Default implementation of {@link org.motechproject.mds.service.EntityService} interface.
 */
@Service
public class EntityServiceImpl extends BaseMdsService implements EntityService {

    private AllEntities allEntities;
    private MDSConstructor constructor;
    private AllTypes allTypes;
    private AllEntityDrafts allEntityDrafts;
    private AllEntityAudits allEntityAudits;

    private static final Logger LOG = LoggerFactory.getLogger(EntityServiceImpl.class);

    // TODO remove this once everything is in db
    private ExampleData exampleData = new ExampleData();

    @Override
    @Transactional
    public EntityDto createEntity(EntityDto entityDto) throws IOException {
        String packageName = ClassName.getPackage(entityDto.getClassName());
        boolean fromUI = StringUtils.isEmpty(packageName);
        String username = getUsername();

        if (fromUI) {
            // in this situation entity.getName() returns a simple name of class
            String className = String.format("%s.%s", Constants.PackagesGenerated.ENTITY, entityDto.getName());
            entityDto.setClassName(className);
        }

        if (allEntities.contains(entityDto.getClassName())) {
            throw new EntityAlreadyExistException();
        }

        Entity entity = allEntities.create(entityDto);

        if (fromUI) {
            LOG.info("Entity from UI - adding default fields");
            addDefaultFields(entity);

            LOG.info("Entity from UI - constructing");
            constructor.constructEntity(entity);
        }

        if (username != null) {
            allEntityAudits.createAudit(entity, username);
        }

        return entity.toDto();
    }

    @Override
    @Transactional
    public DraftResult saveDraftEntityChanges(Long entityId, DraftData draftData) {
        EntityDraft draft = getEntityDraft(entityId);

        if (draftData.isCreate()) {
            createFieldForDraft(draft, draftData);
        } else if (draftData.isEdit()) {
            draftEdit(draft, draftData);
        } else if (draftData.isRemove()) {
            draftRemove(draft, draftData);
        }

        return new DraftResult(draft.isChangesMade(), draft.isOutdated());
    }


    private void draftEdit(EntityDraft draft, DraftData draftData) {
        if (draftData.isForAdvanced()) {
            editAdvancedForDraft(draft, draftData);
        } else if (draftData.isForField()) {
            editFieldForDraft(draft, draftData);
        } else if (draftData.isForSecurity()) {
            editSecurityForDraft(draft, draftData);
        }
    }

    private void editSecurityForDraft(EntityDraft draft, DraftData draftData) {
        List value = (List) draftData.getValue(DraftData.VALUE);
        if (value != null) {
            String securityModeName = (String) value.get(0);
            SecurityMode securityMode = SecurityMode.getEnumByName(securityModeName);


            if (value.size() > 1) {
                List<String> list = (List<String>) value.get(1);
                draft.setSecurity(securityMode, list);
            } else {
                draft.setSecurityMode(securityMode);
            }

            allEntityDrafts.update(draft);
        }
    }

    private void editFieldForDraft(EntityDraft draft, DraftData draftData) {
        String fieldIdStr = draftData.getValue(DraftData.FIELD_ID).toString();

        if (StringUtils.isNotBlank(fieldIdStr)) {
            Long fieldId = Long.valueOf(fieldIdStr);
            Field field = draft.getField(fieldId);

            if (field != null) {
                String path = draftData.getPath();
                List value = (List) draftData.getValue(DraftData.VALUE);

                // Convert to dto for UI updates
                FieldDto dto = field.toDto();
                FieldHelper.setField(dto, path, value);

                // Perform update
                field.update(dto);
                allEntityDrafts.update(draft);
            }
        }
    }

    private void editAdvancedForDraft(EntityDraft draft, DraftData draftData) {
        AdvancedSettingsDto advancedDto = draft.advancedSettingsDto();
        String path = draftData.getPath();
        List value = (List) draftData.getValue(DraftData.VALUE);

        FieldHelper.setField(advancedDto, path, value);

        draft.updateAdvancedSetting(advancedDto);

        allEntityDrafts.update(draft);
    }

    private void createFieldForDraft(EntityDraft draft, DraftData draftData) {
        String typeClass = draftData.getValue(DraftData.TYPE_CLASS).toString();
        String displayName = draftData.getValue(DraftData.DISPLAY_NAME).toString();
        String name = draftData.getValue(DraftData.NAME).toString();

        Type type = allTypes.retrieveByClassName(typeClass);

        if (type == null) {
            throw new NoSuchTypeException();
        }

        Set<Lookup> fieldLookups = new HashSet<>();

        Field field = new Field(draft, displayName, name, fieldLookups);
        field.setType(type);

        if (type.hasSettings()) {
            for (TypeSetting setting : type.getSettings()) {
                field.addSetting(new FieldSetting(field, setting));
            }
        }

        if (type.hasValidation()) {
            for (TypeValidation validation : type.getValidations()) {
                field.addValidation(new FieldValidation(field, validation));
            }
        }

        draft.addField(field);

        allEntityDrafts.update(draft);
    }


    private void draftRemove(EntityDraft draft, DraftData draftData) {
        Long fieldId = Long.valueOf(draftData.getValue(DraftData.FIELD_ID).toString());
        draft.removeField(fieldId);
        allEntityDrafts.update(draft);
    }


    @Override
    @Transactional
    public void abandonChanges(Long entityId) {
        EntityDraft draft = getEntityDraft(entityId);
        if (draft != null) {
            allEntityDrafts.delete(draft);
        }
    }

    @Override
    @Transactional
    public void commitChanges(Long entityId) {
        EntityDraft draft = getEntityDraft(entityId);
        if (draft.isOutdated()) {
            throw new EntityChangedException();
        }

        Entity parent = draft.getParentEntity();
        String username = draft.getDraftOwnerUsername();
        parent.updateFromDraft(draft);

        if (username != null) {
            allEntityAudits.createAudit(parent, username);
        }

        allEntityDrafts.delete(draft);
    }

    @Override
    @Transactional
    public List<EntityDto> listWorkInProgress() {
        String username = getUsername();
        List<EntityDraft> drafts = allEntityDrafts.retrieveAll(username);

        List<EntityDto> entityDtoList = new ArrayList<>();
        for (EntityDraft draft : drafts) {
            if (draft.isChangesMade()) {
                entityDtoList.add(draft.toDto());
            }
        }

        return entityDtoList;
    }

    @Override
    @Transactional
    public List<EntityRecord> getEntityRecords(Long entityId) {
        return exampleData.getEntityRecordsById(entityId);
    }

    @Override
    @Transactional
    public AdvancedSettingsDto getAdvancedSettings(Long entityId) {
        return getAdvancedSettings(entityId, false);
    }

    @Override
    @Transactional
    public AdvancedSettingsDto getAdvancedSettings(Long entityId, boolean committed) {
        if (committed) {
            Entity entity = allEntities.retrieveById(entityId);
            return entity.advancedSettingsDto();
        } else {
            Entity entity = getEntityDraft(entityId);
            return entity.advancedSettingsDto();
        }
    }

    @Override
    @Transactional
    public void addLookups(Long entityId, Collection<LookupDto> lookups) {
        Entity entity = allEntities.retrieveById(entityId);
        assertEntityExists(entity);

        removeLookup(entity, lookups);
        addOrUpdateLookup(entity, lookups);
    }

    private void removeLookup(Entity entity, Collection<LookupDto> lookups) {
        Iterator<Lookup> iterator = entity.getLookups().iterator();

        while (iterator.hasNext()) {
            Lookup lookup = iterator.next();

            // don't remove user defined lookups
            if (!lookup.isReadOnly()) {
                continue;
            }

            boolean found = false;

            for (LookupDto lookupDto : lookups) {
                if (lookup.getLookupName().equalsIgnoreCase(lookupDto.getLookupName())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                iterator.remove();
            }
        }
    }

    private void addOrUpdateLookup(Entity entity, Collection<LookupDto> lookups) {
        for (LookupDto lookupDto : lookups) {
            Lookup lookup = entity.getLookupById(lookupDto.getId());
            List<Field> lookupFields = new ArrayList<>();
            for (String fieldName : lookupDto.getFieldNames()) {
                lookupFields.add(entity.getField(fieldName));
            }

            if (lookup == null) {
                Lookup newLookup = new Lookup(lookupDto, lookupFields);
                entity.addLookup(newLookup);
            } else {
                lookup.update(lookupDto, lookupFields);
            }
        }
    }

    @Override
    @Transactional
    public List<FieldInstanceDto> getInstanceFields(Long instanceId) {
        return exampleData.getInstanceFields(instanceId);
    }

    @Override
    @Transactional
    public List<HistoryRecord> getInstanceHistory(Long instanceId) {
        return exampleData.getInstanceHistoryRecordsById(instanceId);
    }

    @Override
    @Transactional
    public List<PreviousRecord> getPreviousRecords(Long instanceId) {
        return exampleData.getPreviousRecordsById(instanceId);
    }

    @Override
    @Transactional
    public void deleteEntity(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);

        assertWritableEntity(entity);

        if (entity.isDraft()) {
            entity = ((EntityDraft) entity).getParentEntity();
        }

        allEntityDrafts.deleteAll(entity);
        allEntities.delete(entity);
    }

    @Override
    @Transactional
    public List<EntityDto> listEntities() {
        List<EntityDto> entityDtos = new ArrayList<>();

        for (Entity entity : allEntities.retrieveAll()) {
            if (!entity.isDraft()) {
                entityDtos.add(entity.toDto());
            }
        }

        return entityDtos;
    }

    @Override
    @Transactional
    public EntityDto getEntity(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);
        return (entity == null) ? null : entity.toDto();
    }

    @Override
    @Transactional
    public EntityDto getEntityByClassName(String className) {
        Entity entity = allEntities.retrieveByClassName(className);
        return (entity == null) ? null : entity.toDto();
    }

    @Override
    @Transactional
    public List<FieldDto> getFields(Long entityId) {
        return getFields(entityId, true);
    }

    @Override
    @Transactional
    public List<FieldDto> getEntityFields(Long entityId) {
        return getFields(entityId, false);
    }

    private List<FieldDto> getFields(Long entityId, boolean forDraft) {
        Entity entity = (forDraft) ? getEntityDraft(entityId) : allEntities.retrieveById(entityId);

        assertEntityExists(entity);

        // the returned collection is unmodifiable
        List<Field> fields = new ArrayList<>(entity.getFields());

        // for data browser purposes, we sort the fields by their ui display order
        if (!forDraft) {
            Collections.sort(fields, new Comparator<Field>() {
                @Override
                public int compare(Field o1, Field o2) {
                    Long position1 = o1.getUIDisplayPosition();
                    Long position2 = o2.getUIDisplayPosition();

                    if (position1 == null) {
                        return -1;
                    } else if (position2 == null) {
                        return 1;
                    } else {
                        return (position1 > position2) ? 1 : -1;
                    }
                }
            });
        }

        List<FieldDto> fieldDtos = new ArrayList<>();
        for (Field field : fields) {
            fieldDtos.add(field.toDto());
        }

        return fieldDtos;
    }

    @Override
    @Transactional
    public FieldDto findFieldByName(Long entityId, String name) {
        Entity entity = getEntityDraft(entityId);

        Field field = entity.getField(name);

        if (field == null) {
            throw new FieldNotFoundException();
        }

        return field.toDto();
    }

    @Override
    @Transactional
    public EntityDto getEntityForEdit(Long entityId) {
        Entity draft = getEntityDraft(entityId);
        return draft.toDto();
    }

    private String getUsername() {
        String username = null;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            User user = (User) auth.getPrincipal();
            if (user != null) {
                username = user.getUsername();
            }
        }

        return username;
    }

    public EntityDraft getEntityDraft(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);

        assertEntityExists(entity);

        if (entity instanceof EntityDraft) {
            return (EntityDraft) entity;
        }

        // get the user
        String username = getUsername();

        if (username == null) {
            throw new AccessDeniedException("Cannot save draft - no user");
        }

        // get the draft
        EntityDraft draft = allEntityDrafts.retrieve(entity, username);

        if (draft == null) {
            draft = allEntityDrafts.create(entity, username);
        }

        return draft;
    }

    @Override
    @Transactional
    public void addFields(EntityDto entityDto, Collection<FieldDto> fields) {
        Entity entity = allEntities.retrieveById(entityDto.getId());

        assertEntityExists(entity);

        removeFields(entity, fields);

        for (FieldDto fieldDto : fields) {
            Field existing = entity.getField(fieldDto.getBasic().getName());

            if (null != existing) {
                existing.update(fieldDto);
            } else {
                addField(entity, fieldDto);
            }
        }
    }

    private void removeFields(Entity entity, Collection<FieldDto> fields) {
        Iterator<Field> iterator = entity.getFields().iterator();

        while (iterator.hasNext()) {
            Field field = iterator.next();

            // don't remove user defined fields
            if (!field.isReadOnly()) {
                continue;
            }

            boolean found = false;

            for (FieldDto fieldDto : fields) {
                if (field.getName().equalsIgnoreCase(fieldDto.getBasic().getName())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                iterator.remove();
            }
        }
    }

    private void addField(Entity entity, FieldDto fieldDto) {
        FieldBasicDto basic = fieldDto.getBasic();
        String typeClass = fieldDto.getType().getTypeClass();

        Type type = allTypes.retrieveByClassName(typeClass);
        Field field = new Field(
                entity, basic.getDisplayName(), basic.getName(), basic.isRequired(), fieldDto.isReadOnly(),
                (String) basic.getDefaultValue(), basic.getTooltip(), null
        );
        field.setType(type);

        if (type.hasSettings()) {
            for (TypeSetting setting : type.getSettings()) {
                SettingDto settingDto = fieldDto.getSetting(setting.getName());
                FieldSetting fieldSetting = new FieldSetting(field, setting);

                if (null != settingDto) {
                    fieldSetting.setValue(settingDto.getValueAsString());
                }

                field.addSetting(fieldSetting);
            }
        }

        if (type.hasValidation()) {
            for (TypeValidation validation : type.getValidations()) {
                FieldValidation fieldValidation = new FieldValidation(field, validation);

                FieldValidationDto validationDto = fieldDto.getValidation();
                if (null != validationDto) {
                    ValidationCriterionDto criterion = validationDto
                            .getCriterion(validation.getDisplayName());

                    if (null != criterion) {
                        fieldValidation.setValue(criterion.valueAsString());
                        fieldValidation.setEnabled(criterion.isEnabled());
                    }
                }

                field.addValidation(fieldValidation);
            }
        }

        entity.addField(field);
    }

    @Override
    @Transactional
    public void addFilterableFields(EntityDto entityDto, Collection<String> fieldNames) {
        Entity entity = allEntities.retrieveById(entityDto.getId());

        assertEntityExists(entity);

        for (Field field : entity.getFields()) {
            boolean isUIFilterable = fieldNames.contains(field.getName());
            field.setUIFilterable(isUIFilterable);
        }
    }

    @Override
    @Transactional
    public EntityDto updateDraft(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);
        EntityDraft draft = getEntityDraft(entityId);

        allEntityDrafts.setProperties(draft, entity);

        return draft.toDto();
    }

    @Override
    @Transactional
    public LookupDto getLookupByName(Long entityId, String lookupName) {
        Entity entity = allEntities.retrieveById(entityId);
        assertEntityExists(entity);

        Lookup lookup = entity.getLookupByName(lookupName);
        return (lookup == null) ? null : lookup.toDto();
    }

    @Override
    @Transactional
    public List<FieldDto> getDisplayFields(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);
        assertEntityExists(entity);

        List<FieldDto> displayFields = new ArrayList<>();
        for (Field field : entity.getFields()) {
            if (field.isUIDisplayable()) {
                displayFields.add(field.toDto());
            }
        }

        return displayFields;
    }

    @Override
    @Transactional
    public void addDisplayedFields(EntityDto entityDto, Map<String, Long> positions) {
        Entity entity = allEntities.retrieveById(entityDto.getId());

        assertEntityExists(entity);

        List<Field> fields = entity.getFields();

        if (MapUtils.isEmpty(positions)) {
            // all fields will be added

            for (long i = 0; i < fields.size(); ++i) {
                Field field = fields.get((int) i);

                field.setUIDisplayable(true);
                field.setUIDisplayPosition(i);
            }
        } else {
            // only fields in map should be added

            for (Field field : fields) {
                String fieldName = field.getName();

                boolean isUIDisplayable = positions.containsKey(fieldName);
                Long uiDisplayPosition = positions.get(fieldName);

                field.setUIDisplayable(isUIDisplayable);
                field.setUIDisplayPosition(uiDisplayPosition);
            }
        }
    }

    @Transactional
    public void generateDDE(Long entityId) {
        Entity entity = allEntities.retrieveById(entityId);
        assertEntityExists(entity);
        constructor.constructEntity(entity);
    }

    private void assertEntityExists(Entity entity) {
        if (entity == null) {
            throw new EntityNotFoundException();
        }
    }

    private void assertWritableEntity(Entity entity) {
        assertEntityExists(entity);

        if (entity.isDDE()) {
            throw new EntityReadOnlyException();
        }
    }

    private void addDefaultFields(Entity entity) {
        Type longType = allTypes.retrieveByClassName(Long.class.getName());
        Field idField = new Field(entity, "id", longType, true, true);
        idField.addMetadata(new FieldMetadata(idField, "autoGenerated", TRUE));

        entity.addField(idField);
    }

    @Autowired
    public void setAllEntities(AllEntities allEntities) {
        this.allEntities = allEntities;
    }

    @Autowired
    public void setConstructor(MDSConstructor constructor) {
        this.constructor = constructor;
    }

    @Autowired
    public void setAllTypes(AllTypes allTypes) {
        this.allTypes = allTypes;
    }

    @Autowired
    public void setAllEntityDrafts(AllEntityDrafts allEntityDrafts) {
        this.allEntityDrafts = allEntityDrafts;
    }

    @Autowired
    public void setAllEntityAudits(AllEntityAudits allEntityAudits) {
        this.allEntityAudits = allEntityAudits;
    }
}
