package com.alice.components.database;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.support.annotation.Nullable;
import android.util.Log;

import com.alice.components.database.models.EntityDescriptor;
import com.alice.components.database.models.Persistable;
import com.alice.exceptions.DifferentEntityClassesException;
import com.alice.exceptions.NotRegisteredEntityClassUsedException;
import com.alice.exceptions.OperationExecutionException;
import com.alice.tools.Alice;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by Sergey Nalivko.
 * email: snalivko93@gmail.com
 */
public abstract class AbstractEntityManager implements AliceEntityManager {

    public static final String TAG = AbstractEntityManager.class.getSimpleName();

    private Context context;
    private HashSet<Class<?>> entitiesSet;
    protected Map<Class<?>, EntityDescriptor> entityToDescriptor;

    public AbstractEntityManager(Context context) {
        this.context = context;
        List<Class<?>> entityClasses = getEntityClasses();
        final List<EntityDescriptor> entityDescriptors = Alice.databaseTools.generateDescriptorsFor(entityClasses);
        entityToDescriptor = new HashMap<>();
        for (EntityDescriptor descriptor : entityDescriptors) {
            entityToDescriptor.put(descriptor.getEntityClass(), descriptor);
        }
        entitiesSet = new HashSet<>(entityClasses);
    }

    /**
     * Converts cursor data to entities.
     * @param entityClass target entity class
     * @param cursor cursor with data
     * @param count amount of entities to read from cursor. -1 means all possible entities (all form cursor)
     * @return list of converted entities. If no entities was read or any error occurred should return empty list
     */
    protected abstract <T> List<T> convertCursorToEntities(Class<T> entityClass, Cursor cursor, int count);

    /**
     * Returns the projection for the given entity class
     * @param entityClass target entity class
     * @return an array of Strings with column names or null if all columns are requested
     */
    protected abstract <T> String[] getProjection(Class<T> entityClass);

    /**
     * Converts entity to content values object
     * @param entity source entity to be converted
     * @return {@link ContentValues} object, representing source entity
     */
    protected abstract <T> ContentValues convertToContentValues(T entity);

    /**
     * @return list of entity classes managed by this entity manager
     */
    protected abstract List<Class<?>> getEntityClasses();

    /**
     * Generates operations, which are required to save entity
     *
     * @param uri table uri
     * @param entity entity to be saved
     * @return {@link ArrayList} of operations
     */
    protected abstract <T> ArrayList<ContentProviderOperation> generateOperationsToSave(Uri uri, T entity);

    @Override
    public <T> T find(Class<T> entityClass, String id) {
        checkClassRegistered(entityClass);

        Uri uri = getUri(entityClass);

        String idColumn = getIdColumnName(entityClass);

        Cursor cursor = getContext().getContentResolver().query(uri, getProjectionWithRowId(entityClass), idColumn + "=?", new String[]{id}, null);
        List<T> entities = convertCursorToEntities(entityClass, cursor, 1);
        if (entities == null) {
            return null;
        }
        return entities.isEmpty() ? null : entities.get(0);
    }

    @Override
    public <T> List<T> findAll(Class<T> entityClass) {
        checkClassRegistered(entityClass);

        Uri uri = getUri(entityClass);

        Cursor cursor = getContext().getContentResolver().query(uri, getProjectionWithRowId(entityClass), null, null, null);
        List<T> entities = convertCursorToEntities(entityClass, cursor, -1);
        if (entities == null) {
            return new ArrayList<>();
        }
        return entities;
    }

    @Override
    public <T> T save(T entity) {
        if (entity == null) {
            throw new RuntimeException("Attempt to save null entity");
        }
        Class<?> entityClass = entity.getClass();
        checkClassRegistered(entityClass);
        EntityDescriptor descriptor = entityToDescriptor.get(entityClass);
        Uri tableUri = descriptor.getTableUri();
        ArrayList<ContentProviderOperation> operations = generateOperationsToSave(tableUri, entity);

        String tableUriStr = tableUri.toString();

        long prevId = -1;
        ContentProviderResult[] results = applyOperations(operations, descriptor.getAuthority());
        for (ContentProviderResult result : results) {
            long id = ContentUris.parseId(result.uri);
            if (result.uri.toString().contains(tableUriStr) && (prevId != id)) {
                setEntityRowId(entity, id);
                prevId = id;
            }
        }
        return entity;
    }

    @Override
    public <T> T update(T entity) {
        if (entity == null) {
            throw new RuntimeException("Attempt to update null entity");
        }
        //TODO: think how to define is entity persisted if no row id specified
        if (Alice.databaseTools.getRowId(entity) == null) {
            return save(entity);
        }
        checkClassRegistered(entity.getClass());

        ContentValues contentValues = convertToContentValues(entity);

        getContext().getContentResolver().update(getUri(entity.getClass()), contentValues,
                getIdColumnName(entity.getClass()) + "=?", new String[]{getEntityId(entity)});
        return entity;
    }

    @Override
    public <T> boolean delete(Class<T> entityClass, String id) {
        checkClassRegistered(entityClass);
        if (id == null) {
            Log.w(TAG, "Attempt to delete entity with null id");
            return false;
        }
        Uri uri = getUri(entityClass);
        String idColumn = getIdColumnName(entityClass);
        int res = context.getContentResolver().delete(uri, idColumn + "=?", new String[] { id } );
        return res != 0;
    }

    @Override
    public <T> boolean delete(T entity) {
        if (entity == null) {
            Log.w(TAG, "Attempt to delete null entity");
            return false;
        }
        checkClassRegistered(entity.getClass());
        String strId = getEntityId(entity);
        return delete(entity.getClass(), strId);
    }

    @Override
    public <T> Collection<T> saveAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            Log.w(TAG, "Nothing to save. Collection is null or empty");
            return entities;
        }
        Class<?> entityClass = checkAllEntitiesSameClass(entities);
        checkClassRegistered(entityClass);
        EntityDescriptor descriptor = entityToDescriptor.get(entityClass);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>(entities.size());
        Uri tableUri = descriptor.getTableUri();

        Object[] entitiesArray = entities.toArray();
        for (Object entity : entitiesArray) {
            operations.addAll(generateOperationsToSave(tableUri, entity));
        }

        String authority = descriptor.getAuthority();
        ContentProviderResult[] results;
        results = applyOperations(operations, authority);

        for (int i = 0; i < results.length; i++) {
            T entity = ((T) entitiesArray[i]);
            ContentProviderResult result = results[i];
            long id = ContentUris.parseId(result.uri);
            setEntityRowId(entity, id);
        }
        return entities;
    }

    protected ContentProviderResult[] applyOperations(ArrayList<ContentProviderOperation> operations, String authority) {
        ContentProviderResult[] result;
        try {
            result = context.getContentResolver().applyBatch(authority, operations);
        } catch (RemoteException | OperationApplicationException e) {
            throw new OperationExecutionException(e);
        }
        return result;
    }

    @Override
    public <T> Collection<T> updateAll(Collection<T> entities) {
        return null;
    }

    @Override
    public <T> boolean deleteAll(Collection<T> entities) {
        return false;
    }

    @Override
    public <T> boolean deleteAll(Class<T> entityClass, Collection<String> ids) {
        return false;
    }

    @Nullable
    private <T> String getEntityId(T entity) {
        Object id = null;
        Field idField = entityToDescriptor.get(entity.getClass()).getIdField();
        id = Alice.reflectionTools.getValue(idField, entity);
        String strId = null;
        if (id != null) {
            strId = id.toString();
        }
        return strId;
    }

    protected void checkClassRegistered(Class<?> entityClass) {
        if (!entitiesSet.contains(entityClass)) {
            throw new NotRegisteredEntityClassUsedException(entityClass, this.getClass());
        }
    }

    protected <T> Class<?> checkAllEntitiesSameClass(Collection<T> entities) {
        Class<?> cls = null;
        if (entities == null || entities.isEmpty()) {
            Log.w(TAG, "Nothing to check. Collection is null or empty");
            return null;
        }
        Iterator<T> iterator = entities.iterator();
        cls = iterator.next().getClass();
        while (iterator.hasNext()) {
            if (iterator.next().getClass() != cls) {
                throw new DifferentEntityClassesException();
            }
        }
        return cls;
    }

    protected <T> void setEntityRowId(T entity, long rowId) {
        if (entity instanceof Persistable) {
            ((Persistable) entity).setRowId(rowId);
            return;
        }
        Field rowIdField = entityToDescriptor.get(entity.getClass()).getRowIdField();
        if (rowIdField != null) {
            Alice.reflectionTools.setValue(rowIdField, entity, rowId);
        }
    }

    protected <T> Long getEntityRowId(T entity) {
        if (entity instanceof Persistable) {
            return ((Persistable) entity).getRowId();
        }
        Field rowIdField = entityToDescriptor.get(entity.getClass()).getRowIdField();
        if (rowIdField != null) {
            return (Long) Alice.reflectionTools.getValue(rowIdField, entity);
        }
        return null;
    }

    protected <T> Uri getUri(Class<T> entityClass) {
        checkClassRegistered(entityClass);
        return entityToDescriptor.get(entityClass).getTableUri();
    }

    private <T> String getIdColumnName(Class<T> entityClass) {
        return entityToDescriptor.get(entityClass).getIdColumnName();
    }

    private <T> String[] getProjectionWithRowId(Class<T> entityClass) {
        String[] projection = getProjection(entityClass);
        for (String column : projection) {
            if (column.equals(BaseColumns._ID)) {
                return projection;
            }
        }
        String[] projectionExt = new String[projection.length + 1];
        projectionExt[0] = BaseColumns._ID;
        System.arraycopy(projection, 0, projectionExt, 1, projection.length);
        return projectionExt;
    }

    protected  <T> T createEntity(Class<T> entityClass) {
        return Alice.reflectionTools.createEntity(entityClass);
    }

    protected Context getContext() {
        return context;
    }
}
